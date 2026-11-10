package com.smbplayer.tv;
import java.io.*;
import java.nio.file.Files;
public final class CacheRetentionCheck {
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("cache-retention-").toFile();
  try(PlaybackByteCache cache=new PlaybackByteCache(dir,1024*1024,5120)){
   if(cache.capacity()!=5L*1024*1024*1024)throw new AssertionError("32-bit capacity overflow");
   if(dir.listFiles()[0].length()!=0)throw new AssertionError("Cache budget preallocated");
  }
  byte[] bytes=new byte[1024],out=new byte[1];
  try(PlaybackByteCache cache=new PlaybackByteCache(dir,1024,4)){
   for(int i=0;i<3;i++){bytes[0]=(byte)(i+1);cache.writeAt(i*1024,bytes,0,1024);}
   cache.readAt(0,out,0,1);cache.protect(2048,4096);
   cache.writeAt(3072,bytes,0,1024);cache.writeAt(4096,bytes,0,1024);
   if(cache.readAt(0,out,0,1)!=1||out[0]!=1)throw new AssertionError("Recently revisited history evicted");
   if(cache.readAt(1024,out,0,1)!=-1)throw new AssertionError("Old unprotected block retained over newer history");
   if(cache.readAt(2048,out,0,1)!=1||cache.readAt(3072,out,0,1)!=1)throw new AssertionError("Playback window evicted");
  }
  if(dir.list().length!=0)throw new AssertionError("Cache not cleaned");dir.delete();
  System.out.println("5GiB long capacity, lazy allocation, recent history, protected window and cleanup passed");
 }
}
