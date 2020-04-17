package com.highgreat.sven.bitmapcache;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;

import com.highgreat.sven.bitmapcache.disk.DiskLruCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class ImageCache {

    private static ImageCache instance;
    private DiskLruCache diskLruCache;
    private LruCache<String, Bitmap> bitmapLruCache;
    BitmapFactory.Options options=new BitmapFactory.Options();

    //定义一个复用池
   public static  Set<WeakReference<Bitmap>> reuseablePool;

   //引用队列
    ReferenceQueue referenceQueue;
    boolean shutDown;

    public static ImageCache getInstance(){
        synchronized (ImageCache.class){
            if(instance == null){
                instance = new ImageCache();
            }
        }
        return instance;
    }

    public void init(Context context,String dir){

        //创建一个带锁的弱引用集合
        reuseablePool = Collections.synchronizedSet(new HashSet<WeakReference<Bitmap>>());

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        //获取程序最大可用内存 单位是M
        int memoryClass=am.getMemoryClass();
        bitmapLruCache = new LruCache<String, Bitmap>(memoryClass/8*1024*1024){
            /**
             * @param key
             * @param value
             * @return 计算一个元素占用的内存大小
             */
            @Override
            protected int sizeOf(String key, Bitmap value) {
                //19之前   必需同等大小，才能复用  inSampleSize=1
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT){
                    return value.getAllocationByteCount();
                }
                return value.getByteCount();
            }

            /**
             * 当lru满了，bitmap从lru中移除对象时，会回调
             * @param evicted
             * @param key
             * @param oldValue
             * @param newValue
             */
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if(oldValue.isMutable()){
                    //把这些图片放到一个复用池中
                    reuseablePool.add(new WeakReference<Bitmap>(oldValue,referenceQueue));
                }else{
                    //oldValue就是移出来的对象
                    oldValue.recycle();
                }
            }
        };

        try {
            diskLruCache = DiskLruCache.open(new File(dir), BuildConfig.VERSION_CODE, 1, 10 * 1024 * 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }

        getReferenceQueue();
    }

    private ReferenceQueue<Bitmap> getReferenceQueue(){
        if(null == referenceQueue ){
            //当弱引用需要被回收的时候，会进入到这个队列
            referenceQueue = new ReferenceQueue<Bitmap>();
            //单开一个线程，去扫描引用队列中GC扫到的内容，交到native层去释放
            Thread clearReferenceQueque = new Thread(new Runnable() {
                @Override
                public void run() {
                    while(!shutDown){
                        //remove是阻塞式的
                        Log.i("Sven","while循环");
                        try {
                            Reference<Bitmap> reference = referenceQueue.remove();
                            Bitmap bitmap = reference.get();
                            if(null != bitmap && !bitmap.isRecycled()){
                                bitmap.recycle();
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            clearReferenceQueque.start();
        }
        return referenceQueue;
    }


    public void putBitmapToMemeory(String key,Bitmap bitmap){
        bitmapLruCache.put(key,bitmap);
    }

    public Bitmap getBitmapFromMemory(String key){
        return bitmapLruCache.get(key);
    }

    //获取复用池中的内容
    public Bitmap getReuseable(int w,int h,int inSampleSize){
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB){
            return null;
        }
        Bitmap reuseable = null;
        Iterator<WeakReference<Bitmap>> iterator = reuseablePool.iterator();
        while(iterator.hasNext()){
            WeakReference<Bitmap> next = iterator.next();
            Bitmap bitmap = next.get();
            if(null != bitmap){
                //检查是否可以复用
                if(checkInBitmap(bitmap, w, h, inSampleSize)){
                    reuseable = bitmap;
                    iterator.remove();
                    break;
                }else{
                    iterator.remove();
                }
            }
        }
        return reuseable;
    }

    //只有新图片的占用内存大小要小于被复用图片的内存大小，那么新图片才可以使用被复用的图片的内存区域，而不用新开辟内存空间
    private boolean checkInBitmap(Bitmap bitmap, int w, int h, int inSampleSize) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT){
            return bitmap.getWidth()==w && bitmap.getHeight()==h && inSampleSize==1;
        }

        if(inSampleSize >= 1){
            w/=inSampleSize;
            h/=inSampleSize;
        }
        //获取图片占用内存大小
        int byteCount=w*h*getPixelsCount(bitmap.getConfig());
        return byteCount<=bitmap.getAllocationByteCount();
    }

    private int getPixelsCount(Bitmap.Config config) {
        if(config==Bitmap.Config.ARGB_8888){
            return 4;
        }
        return 2;
    }

    /**
     * 从磁盘缓存中取
     * @param key
     * @param reuseable
     * @return
     */
    public Bitmap getBitmapFromDisk(String key,Bitmap reuseable){
        DiskLruCache.Snapshot snapshot = null;
        Bitmap bitmap = null;
        try {
            snapshot = diskLruCache.get(key);
            if(null == snapshot){
                return null;
            }
            //获取文件输入流，读取BitMap;
            InputStream inputStream = snapshot.getInputStream(0);
            options.inMutable = true;//可以复用，即下一张图片可以复用这张图片的内存空间，不用重新开辟空间
            options.inBitmap = reuseable;
            bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            if(null != bitmap){
                //存入内存缓存
                bitmapLruCache.put(key,bitmap);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(null != snapshot){
                snapshot.close();
            }
        }
        return bitmap;
    }

    /**
     * 加入磁盘缓存
     * @param key
     * @param bitmap
     */
    public void putBitMapToDisk(String key,Bitmap bitmap){
        DiskLruCache.Snapshot snapshot = null;
        OutputStream outputStream = null;
        try {
            snapshot = diskLruCache.get(key);
            //如果缓存中不存在这个文件
            if(null == snapshot){
                //生成这个文件
                DiskLruCache.Editor edit = diskLruCache.edit(key);
                if(null != edit){
                    outputStream = edit.newOutputStream(0);
//                    （1）使用此方法压缩bitmap以后，图片的宽高大小都不会变化，每个像素大小也不会变化，所以图片在内存中的实际大小不会变化，
//                    （2）第二个参数是压缩比重，图片存储在磁盘上的大小会根据这个值变化。值越小存储在磁盘的图片文件越小，
//                    （3）第一个参数如果是Bitmap.CompressFormat.PNG,那不管第二个值如何变化，图片大小都不会变化，不支持png图片的压缩
                    bitmap.compress(Bitmap.CompressFormat.JPEG,100,outputStream);
                    edit.commit();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(null != snapshot){
                snapshot.close();
            }

            if(null != outputStream){
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }




}
