package com.callanna.rxdownload.api;

import android.content.Context;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.callanna.rxdownload.Utils;
import com.callanna.rxdownload.db.DBManager;
import com.callanna.rxdownload.db.DownLoadBean;
import com.callanna.rxdownload.db.DownLoadStatus;
import com.callanna.rxdownload.db.DownloadRange;
import com.callanna.rxdownload.file.FileHelper;

import org.reactivestreams.Publisher;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;
import retrofit2.Response;
import retrofit2.Retrofit;

import static android.os.Environment.DIRECTORY_DOWNLOADS;
import static android.os.Environment.getExternalStoragePublicDirectory;
import static com.callanna.rxdownload.Utils.empty;
import static com.callanna.rxdownload.Utils.fileName;
import static com.callanna.rxdownload.Utils.log;
import static com.callanna.rxdownload.Utils.mkdirs;
import static com.callanna.rxdownload.db.DownLoadStatus.COMPLETED;
import static com.callanna.rxdownload.db.DownLoadStatus.FAILED;
import static com.callanna.rxdownload.db.DownLoadStatus.PAUSED;
import static com.callanna.rxdownload.db.DownLoadStatus.STARTED;
import static com.callanna.rxdownload.db.DownLoadStatus.WAITING;
import static java.io.File.separator;

/**
 * Author: Season(ssseasonnn@gmail.com)
 * Date: 2016/11/2
 * Time: 09:39
 * Download helper
 */
public class DownloadHelper {
    public static final String TEST_RANGE_SUPPORT = "bytes=0-";
    private static final CharSequence CACHE = ".cache";
    public static final String TMP_SUFFIX = ".tmp";  //temp file
    public static final String LMF_SUFFIX = ".lmf";  //last modify file
    private int maxRetryCount = 3;
    private int maxThreads = 3;

    private String defaultSavePath= "";
    private String filePath= "";
    private String tempPath= "";
    private String lmfPath= "";
    private String cachePath = "";

    private long contentLength;
    private String lastModify;

    private boolean rangeSupport = false;
    private boolean serverFileChanged = false;

    private FileHelper fileHelper;
    private DownloadApi downloadApi;
    private DBManager dbManager;

    public DownloadHelper(Context context) {
        downloadApi = RetrofitProvider.getInstance().create(DownloadApi.class);
        defaultSavePath = getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS).getPath();
        dbManager = DBManager.getSingleton(context.getApplicationContext());
        fileHelper = new FileHelper(maxThreads);

         cachePath = TextUtils.concat(defaultSavePath, separator, CACHE).toString();
        mkdirs(defaultSavePath, cachePath);


    }

    public void setRetrofit(Retrofit retrofit) {
        downloadApi = retrofit.create(DownloadApi.class);
    }

    public void setDefaultSavePath(String defaultSavePath) {
        this.defaultSavePath = defaultSavePath;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    /**
     * return Files
     *
     * @param url url
     * @return Files = {file,tempFile,lmfFile}
     */
    @Nullable
    public File[] getFiles(String url) {
        DownLoadBean record = dbManager.searchByUrl(url);
        if (record == null) {
            return null;
        } else {
            return Utils.getFiles(record.getSaveName(), record.getSavePath());
        }
    }

    /**
     * prepare normal download, create files and save last-modify.
     *
     * @throws IOException
     * @throws ParseException
     */
    public void prepareNormalDownload() throws IOException, ParseException {
        fileHelper.prepareDownload(lastModifyFile(), file(), contentLength, lastModify);
    }

    /**
     * prepare range download, create necessary files and save last-modify.
     *
     * @throws IOException
     * @throws ParseException
     */
    public void prepareRangeDownload() throws IOException, ParseException {
        fileHelper.prepareDownload(lastModifyFile(), tempFile(), file(), contentLength, lastModify);
    }

    /**
     * Read download range from record file.
     *
     * @param index index
     * @return
     * @throws IOException
     */
    public DownloadRange readDownloadRange(int index) throws IOException {
        return fileHelper.readDownloadRange(tempFile(), index);
    }


    /**
     * Normal download save.
     *
     * @param e        emitter
     * @param response response
     */
    public void save(FlowableEmitter<DownLoadStatus> e, ResponseBody response) {

        fileHelper.saveFile(e, file(), response);
    }

    /**
     * Range download save
     *
     * @param emitter  emitter
     * @param index    download index
     * @param response response
     * @throws IOException
     */
    public void save(FlowableEmitter<DownLoadStatus> emitter, int index, ResponseBody response)
            throws IOException {
        fileHelper.saveFile(emitter, index, tempFile(), file(), response);
    }

    /**
     * Normal download request.
     *
     * @return response
     */
    public Publisher<DownLoadStatus> download(DownLoadBean bean) throws InterruptedException {
        String name = bean.getSaveName();
        String path = bean.getSavePath();
        Log.d("duanyl", "download:getSaveName  "+name);
        Log.d("duanyl", "download:getSavePath  "+path+"," +bean.getIsSupportRange());
        filePath = bean.getSavePath() +"/"+bean.getSaveName();
        tempPath = cachePath+ File.separator+name+TMP_SUFFIX;
        lmfPath =  cachePath+separator+name+LMF_SUFFIX ;
        if (bean.getIsSupportRange()) {
            List<Publisher<DownLoadStatus>> tasks = new ArrayList<>();
            for (int i = 0; i < maxThreads; i++) {
                TimeUnit.MILLISECONDS.sleep(200);
                tasks.add(rangeDownload(i, bean.getUrl()));
            }
            return Flowable.mergeDelayError(tasks);
        } else {
            return download(bean.getUrl());
        }
    }

    /**
     * Normal download request.
     *
     * @return response
     */
    public Publisher<DownLoadStatus> download(String url) {

        return downloadApi.download(null, url)
                .subscribeOn(Schedulers.io())  //Important!
                .flatMap(new Function<Response<ResponseBody>, Publisher<DownLoadStatus>>() {
                    @Override
                    public Publisher<DownLoadStatus> apply(final Response<ResponseBody> response) throws Exception {
                        return save(-1, response.body());
                    }
                });
    }

    /**
     * Range download request
     *
     * @param index download index
     * @return response
     */
    public Publisher<DownLoadStatus> rangeDownload(final int index, final String url) {
        return Flowable
                .create(new FlowableOnSubscribe<DownloadRange>() {
                    @Override
                    public void subscribe(FlowableEmitter<DownloadRange> e) throws Exception {
                        DownloadRange range = readDownloadRange(index);
                        if (range.legal()) {
                            e.onNext(range);
                        }
                        e.onComplete();
                    }
                }, BackpressureStrategy.ERROR)
                .flatMap(new Function<DownloadRange, Publisher<Response<ResponseBody>>>() {
                    @Override
                    public Publisher<Response<ResponseBody>> apply(DownloadRange range)
                            throws Exception {
                        String rangeStr = "bytes=" + range.start + "-" + range.end;
                        return downloadApi.download(rangeStr, url);
                    }
                })
                .subscribeOn(Schedulers.io())  //Important!
                .flatMap(new Function<Response<ResponseBody>, Publisher<DownLoadStatus>>() {
                    @Override
                    public Publisher<DownLoadStatus> apply(Response<ResponseBody> response) throws Exception {
                        return save(index, response.body());
                    }
                });
    }

    /**
     * 保存断点下载的文件,以及下载进度
     *
     * @param index    下载编号
     * @param response 响应值
     * @return Flowable
     */
    private Publisher<DownLoadStatus> save(final int index, final ResponseBody response) {

        Flowable<DownLoadStatus> flowable = Flowable.create(new FlowableOnSubscribe<DownLoadStatus>() {
            @Override
            public void subscribe(FlowableEmitter<DownLoadStatus> emitter) throws Exception {
                if (index == -1) {
                    save(emitter, response);
                } else {
                    save(emitter, index, response);
                }
            }
        }, BackpressureStrategy.LATEST)
                .replay(1)
                .autoConnect();
        return flowable.throttleFirst(100, TimeUnit.MILLISECONDS).mergeWith(flowable.takeLast(1))
                .subscribeOn(Schedulers.newThread());
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public boolean isSupportRange() {
        return rangeSupport;
    }

    public void setRangeSupport(boolean rangeSupport) {
        this.rangeSupport = rangeSupport;
    }

    public boolean isFileChanged() {
        return serverFileChanged;
    }

    public void setFileChanged(boolean serverFileChanged) {
        this.serverFileChanged = serverFileChanged;
    }

    public long getContentLength() {
        return contentLength;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public void setLastModify(String lastModify) {
        this.lastModify = lastModify;
    }

    public String getSaveName() {
        return filePath;
    }

    public void setSaveName(String saveName) {
        filePath = saveName;
    }

    public File file() {
        return new File(filePath );
    }

    public File tempFile() {
        return new File(tempPath);
    }

    public File lastModifyFile() {
        return new File(lmfPath);
    }

    public boolean fileComplete() {
        return file().length() == contentLength;
    }

    public boolean tempFileDamaged() throws IOException {
        return fileHelper.tempFileDamaged(tempFile(), contentLength);
    }

    public String readLastModify() throws IOException {
        return fileHelper.readLastModify(lastModifyFile());
    }

    public boolean fileNotComplete() throws IOException {
        return fileHelper.fileNotComplete(tempFile());
    }

    public File[] getFiles() {
        return new File[]{file(), tempFile(), lastModifyFile()};
    }


    public void start(DownLoadBean bean) {
        if (dbManager.recordNotExists(bean.getUrl())) {
            dbManager.add(bean);
        } else {
            bean.getStatus().setStatus(STARTED);
            dbManager.updateStatusByUrl(bean.getUrl(), STARTED);
        }
    }

    public void update(String url, DownLoadStatus status) {
        dbManager.updateStatusByUrl(url, status);
    }

    public void error(String url) {
        dbManager.updateStatusByUrl(url, FAILED);
    }

    public void complete(String url) {
        dbManager.updateStatusByUrl(url, COMPLETED);
    }

    public void cancel(String url) {
        dbManager.updateStatusByUrl(url, PAUSED);
    }

    public Flowable<DownLoadBean> prepare(final String url) {
      Flowable flowable = Flowable.create(new FlowableOnSubscribe<DownLoadBean>() {
          @Override
          public void subscribe(@NonNull final FlowableEmitter<DownLoadBean> e) throws Exception {
              Observable.just(1).flatMap(new Function<Integer, ObservableSource<DownLoadBean>>() {
                  @Override
                  public ObservableSource<DownLoadBean> apply(@NonNull Integer integer) throws Exception {
                      DownLoadBean downLoadBean = dbManager.searchByUrl(url);
                      if(downLoadBean.getSaveName() == null || downLoadBean.getSaveName().equals("")){
                          return checkRange(downLoadBean);
                      }else {
                          return checkFile(downLoadBean, downLoadBean.getLastModify());
                      }
                  }
              }).subscribeOn(Schedulers.io())
                .observeOn(Schedulers.newThread())
                .subscribe(new Consumer<DownLoadBean>() {
                  @Override
                  public void accept(@NonNull DownLoadBean bean) throws Exception {
                      Log.d("duanyl", "accept: prepare");
                      e.onNext(bean);
                      e.onComplete();
                  }
              });

          }
      },BackpressureStrategy.ERROR);
        return flowable ;
    }

    public Observable<DownLoadStatus> startDownLoad(final DownLoadBean bean) {
        Log.d("duanyl", "startDownLoad: ");
        return Flowable.just(1).throttleFirst(500,TimeUnit.MILLISECONDS)
                .takeLast(1)
                .flatMap(new Function<Integer, Publisher<DownLoadStatus>>() {
                @Override
                public Publisher<DownLoadStatus> apply(@NonNull Integer integer) throws Exception {
                      return download(bean);
                   }
                })
                .subscribeOn(Schedulers.io())
                .map(new Function<DownLoadStatus, DownLoadStatus>() {
                    @Override
                    public DownLoadStatus apply(@NonNull DownLoadStatus downLoadStatus) throws Exception {
                        dbManager.updateStatusByUrl(bean.getUrl(), downLoadStatus);
                        return downLoadStatus;
                    }
                })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        log(throwable.getMessage());
                        throwable.printStackTrace();
                        dbManager.updateStatusByUrl(bean.getUrl(), DownLoadStatus.FAILED);
                    }
                })
                .doOnComplete(new Action() {
                    @Override
                    public void run() throws Exception {
                        log("complete");
                        dbManager.updateStatusByUrl(bean.getUrl(), DownLoadStatus.COMPLETED);
                    }
                })

                .doOnCancel(new Action() {
                    @Override
                    public void run() throws Exception {
                        log("cancel");
                        dbManager.updateStatusByUrl(bean.getUrl(), DownLoadStatus.CANCELED);
                    }
                })
                .doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        log("finish");
                        dbManager.updateStatusByUrl(bean.getUrl(), DownLoadStatus.COMPLETED);
                    }
                })
                .toObservable();
    }


    private ObservableSource<DownLoadBean> checkUrl(final String url) {
        Log.d("duanyl", "checkUrl: ");
        return downloadApi.checkByGet(url)
                .subscribeOn(Schedulers.io())
                .flatMap(new Function<Response<ResponseBody>, ObservableSource<DownLoadBean>>() {
                    @Override
                    public ObservableSource<DownLoadBean> apply(@NonNull Response<ResponseBody> voidResponse) throws Exception {
                        Log.d("duanyl", "apply: 1 checkUrl "+voidResponse.isSuccessful());
                        DownLoadBean bean = new DownLoadBean(url);
                        if (!voidResponse.isSuccessful()) {
                            return checkRange(bean);
                        } else {
                            Log.d("duanyl", "apply: checkUrl");
                            bean = saveFileInfo(url, voidResponse, WAITING);
                            dbManager.add(bean);
                            return Observable.just(bean);
                        }
                    }
                });
    }

    /**
     * http checkRangeByHead request,checkRange need info.
     *
     * @return empty Observable
     */
    private ObservableSource<DownLoadBean> checkRange(final DownLoadBean bean) {
        Log.d("duanyl", "checkRange: " +bean.getUrl());
        return downloadApi.checkRangeByHead(TEST_RANGE_SUPPORT, bean.getUrl())
                .flatMap(new Function<Response<Void>, ObservableSource<DownLoadBean>>() {
                    @Override
                    public ObservableSource<DownLoadBean> apply(@NonNull Response<Void> response) throws Exception {
                        Log.d("duanyl", "checkRange accept: "+ response.isSuccessful());
                        if(response.isSuccessful()) {
                            saveFileInfo(bean, response, WAITING);
                            bean.setIsSupportRange(!Utils.notSupportRange(response));
                        }
                        Log.d("duanyl", "checkRange accept: "+bean.getIsSupportRange());
                        bean.setStatus(new DownLoadStatus(DownLoadStatus.WAITING));
                        if(dbManager!= null) {
                            dbManager.add(bean);
                        }
                        Log.d("duanyl", "checkRange apply: ");
                        return Observable.just(bean);
                    }
                }).doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(@NonNull Throwable throwable) throws Exception {
                        Log.d("duanyl", "checkRange accept: "+throwable.getMessage());
                        throwable.printStackTrace();
                    }
                }) ;
    }
    /**
     * http checkRangeByHead request,checkRange need info, check whether if server file has changed.
     *
     * @return empty Observable
     */
    private ObservableSource<DownLoadBean> checkFile(final DownLoadBean bean, final String lastModify) {
        Log.d("duanyl", "checkFile: ");
        return downloadApi.checkFileByHead(lastModify, bean.getUrl())
                .doOnNext(new Consumer<Response<Void>>() {
                    @Override
                    public void accept(Response<Void> response) throws Exception {
                        Log.d("duanyl", "accept: checkFile"+response.code());
                        if (response.code() == 304) {
                            delete(bean);
                        }else{
                            dbManager.update(bean);
                        }
                    }
                })
                .flatMap(new Function<Response<Void>, ObservableSource<DownLoadBean>>() {
                    @Override
                    public ObservableSource<DownLoadBean> apply(@NonNull Response<Void> voidResponse) throws Exception {
                        checkRange(bean);
                        return Observable.just(bean);
                    }
                }).subscribeOn(Schedulers.io()).observeOn(Schedulers.newThread());
    }

    public void delete(DownLoadBean bean) {
        Log.d("duanyl", "delete: ");
        dbManager.delete(bean.getUrl());
        file().delete();
        tempFile().delete();
        lastModifyFile().delete();
    }
    public void deleteAll(){
        Log.d("duanyl", "deleteAll: ");
        dbManager.searchDownloadByAll().flatMap(new Function<List<DownLoadBean>, ObservableSource<?>>() {
            @Override
            public ObservableSource<?> apply(@NonNull List<DownLoadBean> downLoadBeen) throws Exception {
               for(DownLoadBean bean :downLoadBeen){
                   delete(bean);
               }
             return null;
            }
        });
    }

    /**
     * Save file info
     *
     * @param url      key
     * @param response response
     */
    public DownLoadBean saveFileInfo(String url, Response<?> response, int flag) {
        Log.d("duanyl", "saveFileInfo: ");
        DownLoadBean bean = new DownLoadBean(url);
        DownLoadStatus downLoadStatus = new DownLoadStatus(flag);
        if (empty(bean.getSaveName())) {
            bean.setSaveName(fileName(url, response));
        }
        downLoadStatus.setDownloadSize(Utils.contentLength(response));
        bean.setLastModify((Utils.lastModify(response)));
        return bean;
    }

    /**
     * Save file info
     *
     * @param response response
     */
    public void saveFileInfo(DownLoadBean bean, Response<?> response, int flag) {
        DownLoadStatus downLoadStatus = new DownLoadStatus(flag);
        if (empty(bean.getSaveName())) {
            bean.setSaveName(fileName(bean.getUrl(), response));
            Log.d("duanyl", "saveFileInfo: "+defaultSavePath.toString());
            bean.setSavePath(defaultSavePath.toString());
        }
        downLoadStatus.setDownloadSize(Utils.contentLength(response));
        bean.setLastModify((Utils.lastModify(response)));
    }
}
