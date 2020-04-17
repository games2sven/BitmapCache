package com.highgreat.sven.bitmapcache;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

public class MyAdapter extends BaseAdapter {

    private Context context;

    public MyAdapter(Context context) {
        this.context = context;
    }


    @Override
    public int getCount() {
        return 9999;
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder viewHolder = null;
        if(convertView == null){
            convertView = LayoutInflater.from(context).inflate(R.layout.item,parent,false);
            viewHolder = new ViewHolder(convertView);
            convertView.setTag(viewHolder);
        }else {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        Bitmap bitmap = ImageCache.getInstance().getBitmapFromMemory(String.valueOf(position));
        if(null == bitmap){
            //如果内存没数据，就去复用池找
            Bitmap reuseable = ImageCache.getInstance().getReuseable(60,60,1);
            //从磁盘找
            bitmap = ImageCache.getInstance().getBitmapFromDisk(String.valueOf(position),reuseable);
            if(null == bitmap){//如果磁盘中也没缓存,就从网络下载
                bitmap = ImageResize.resizeBitmap(context,R.mipmap.ic_launcher,80,80,false,reuseable);
                ImageCache.getInstance().putBitmapToMemeory(String.valueOf(position),bitmap);
                ImageCache.getInstance().putBitMapToDisk(String.valueOf(position),bitmap);
                Log.i("Sven","从网络加载了数据");
            }else{
                Log.i("Sven","从磁盘中加载了数据");
            }
        }else{
            Log.i("Sven","从内存中加载了数据");
        }
        viewHolder.iv.setImageBitmap(bitmap);
        return convertView;
    }

    class ViewHolder {
        ImageView iv;

        ViewHolder(View view) {
            iv = view.findViewById(R.id.iv);
        }
    }

}
