package com.smbplayer.tv;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ForwardCacheCheck {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static byte value(long p){return (byte)(p*31^(p>>>19));}
 static void waitFor(java.util.function.BooleanSupplier test,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!test.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(10);}}
 public static void main(String[] args)throws Exception{
  long mb=1024*1024;check(ForwardCache.diskCapacity(6L*1024*mb,4L*1024*mb)==1024*mb,"Large cache cap");check(ForwardCache.diskCapacity(10*mb,4L*1024*mb)==10*mb,"Small file cap");check(ForwardCache.diskCapacity(1024*mb,64*mb)==0,"Low disk reserve");
  File dir=Files.createTempDirectory("forward-cache-check").toFile();AtomicInteger open=new AtomicInteger(),max=new AtomicInteger(),in=new AtomicInteger(),maxIn=new AtomicInteger();AtomicLong network=new AtomicLong();Map<Long,AtomicInteger> calls=new ConcurrentHashMap<>();
  RangeReadAhead.Factory factory=()->{int n=open.incrementAndGet();max.accumulateAndGet(n,Math::max);return new RangeReadAhead.Reader(){boolean closed;public void read(long p,byte[] bytes,int length)throws IOException{if(closed)throw new IOException("Closed");int count=in.incrementAndGet();maxIn.accumulateAndGet(count,Math::max);try{calls.computeIfAbsent(p/ForwardCache.CHUNK,k->new AtomicInteger()).incrementAndGet();try{Thread.sleep(4);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}for(int i=0;i<length;i++)bytes[i]=value(p+i);network.addAndGet(length);}finally{in.decrementAndGet();}}public void close(){if(!closed){closed=true;open.decrementAndGet();}}};};
  try(PlaybackByteCache disk=new PlaybackByteCache(dir,(int)mb,32);ForwardCache cache=new ForwardCache(disk,6L*1024*mb,factory,8)){
   cache.policy(12*mb,0,false);cache.demand(0);waitFor(()->cache.bufferedAhead()>=12*mb,"Did not prefill independently of playback reads");waitFor(()->cache.inFlight()==0,"Prefill did not stop at target");long before=network.get();Thread.sleep(250);check(network.get()==before,"Kept downloading beyond byte high watermark");
   byte[] b=new byte[2048];cache.read(1234,b,0,b.length);for(int i=0;i<b.length;i++)check(b[i]==value(1234+i),"Cached data mismatch");check(network.get()==before,"Cache hit caused network read");
   ExecutorService clients=Executors.newFixedThreadPool(4);try{List<Future<?>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(clients.submit(()->{try{byte[] out=new byte[2048];cache.read(60*mb+11,out,0,out.length);for(int j=0;j<out.length;j++)check(out[j]==value(60*mb+11+j),"Shared read bytes differ");}catch(Exception e){throw new RuntimeException(e);}}));for(Future<?> f:futures)f.get();}finally{clients.shutdownNow();}
   check(calls.get(60*mb/ForwardCache.CHUNK).get()==1,"Duplicate concurrent network download");
   cache.policy(8*mb,0,false);cache.reposition(5L*1024*mb);waitFor(()->cache.bufferedAhead()>=8*mb,"Seek did not prefill new long-offset window");cache.read(5L*1024*mb+27,b,0,b.length);for(int i=0;i<b.length;i++)check(b[i]==value(5L*1024*mb+27+i),"Large offset corruption");
   cache.policy(32*mb,70000,true);waitFor(()->cache.inFlight()==0,"Time high watermark did not stop jobs");long stop=network.get();Thread.sleep(250);check(network.get()==stop,"Time high watermark ignored");cache.policy(32*mb,10000,true);waitFor(()->network.get()>stop,"Time low watermark did not restart prefill");
   check(max.get()<=8&&maxIn.get()<=8,"Worker/reader bound exceeded");check(cache.inFlight()<=18,"In-flight memory bound exceeded");
  }
  check(open.get()==0,"Reader leaked");check(dir.list().length==0,"Disk cache leaked");dir.delete();
  System.out.println("Independent prefill, byte/time watermarks, shared reads, 5GiB seek, integrity, bounds and cleanup passed");
 }
}
