package com.smbplayer.tv;
import java.io.*;
public final class IndexedSeekCheck {
 public static void main(String[] args)throws Exception{
  for(String path:args){File f=new File(path);try(RandomAccessFile input=new RandomAccessFile(f,"r")){
   MediaBufferIndex index=MediaBufferIndex.open(new MediaBufferIndex.Reader(){public long length(){return f.length();}public void read(long p,byte[] out,int o,int n)throws IOException{input.seek(p);input.readFully(out,o,n);}});
   long last=-1;for(long time:new long[]{0,1000,10000,30000,60000,90000}){long at=index.byteOffset(time);if(at<0||at>=f.length()||at<last)throw new AssertionError("Bad indexed seek anchor");last=at;}
   System.out.println(index.getClass().getSimpleName()+": monotonic real byte anchors passed");
  }}
 }
}
