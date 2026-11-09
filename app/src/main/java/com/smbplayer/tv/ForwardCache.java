// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded download pipeline shared by the HTTP reader and independent disk prefill. */
final class ForwardCache implements Closeable {
    static final int CHUNK=256*1024, MAX_JOBS=16;
    private final PlaybackByteCache cache;
    private final RangeReadAhead.Factory factory;
    private final long size,budget;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService pump;
    private final Map<Long,Job> jobs=new HashMap<>();
    private final Set<RangeReadAhead.Reader> readers=ConcurrentHashMap.newKeySet();
    private final ThreadLocal<RangeReadAhead.Reader> local=new ThreadLocal<>();
    private final AtomicLong sequence=new AtomicLong();
    private final AtomicBoolean pumpQueued=new AtomicBoolean();
    private volatile boolean closed,anchored,fill=true,timeControlled;
    private volatile long anchor,high,low,generation,retryAt;
    private int failures;
    private volatile boolean byteRefill=true;

    ForwardCache(PlaybackByteCache cache,long size,RangeReadAhead.Factory factory,int concurrency){
        this.cache=cache;this.size=size;this.factory=factory;
        budget=Math.max(CHUNK,cache.capacity()-Math.min(8L*1024*1024,cache.capacity()/8));
        high=Math.min(budget,64L*1024*1024);low=high/2;
        workers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new PriorityBlockingQueue<>(),r->{Thread t=new Thread(r,"smb-disk-prefill");t.setDaemon(true);return t;});
        pump=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"smb-prefill-control");t.setDaemon(true);return t;});
        pump.scheduleWithFixedDelay(this::wake,0,200,TimeUnit.MILLISECONDS);
    }
    static long diskCapacity(long fileSize,long free){
        long unit=1024L*1024,reserve=Math.min(512*unit,Math.max(64*unit,free/4));
        long available=Math.max(0,Math.min(1024*unit,free-reserve));
        return Math.min(available/unit,1+(Math.max(1,fileSize)-1)/unit)*unit;
    }
    long capacity(){return cache.capacity();}
    long bufferedAhead(){return anchored?Math.max(0,Math.min(size,cache.contiguousEnd(anchor))-anchor):0;}
    long anchor(){return anchor;}
    int inFlight(){synchronized(jobs){return jobs.size();}}
    void policy(long bytes,long aheadMs,boolean accurate){
        high=accurate?budget:Math.max(CHUNK,Math.min(budget,bytes));low=high/2;timeControlled=accurate;
        if(accurate){if(aheadMs>=60000)fill=false;else if(aheadMs<30000)fill=true;}
        else fill=true;
        wake();
    }
    void demand(long position){
        if(position<0||position>=size||closed)return;
        if(!anchored||position<anchor-2L*CHUNK||position>anchor+8L*1024*1024)reposition(position);
        else {anchor=position;protect();wake();}
    }
    void advance(long position){if(!anchored)reposition(position);else{anchor=Math.max(0,Math.min(size-1,position));protect();wake();}}
    void reposition(long position){
        if(closed)return;
        generation++;anchor=Math.max(0,Math.min(size-1,position));anchored=true;fill=true;byteRefill=true;protect();
        synchronized(jobs){for(Job job:new ArrayList<>(jobs.values()))if(!job.started&&job.priority>0){workers.remove(job);job.cancel(false);}}
        wake();
    }
    void suspend(){
        anchored=false;generation++;
        synchronized(jobs){for(Job job:new ArrayList<>(jobs.values()))if(!job.started&&job.priority>0){workers.remove(job);job.cancel(false);}}
    }
    private void protect(){cache.protect(Math.max(0,anchor-2L*1024*1024),Math.min(size,anchor+high));}
    int read(long position,byte[] out,int offset,int count)throws IOException{
        if(closed)throw new IOException("Forward cache closed");if(position>=size)return -1;
        int cached=cache.readAt(position,out,offset,(int)Math.min(count,size-position));if(cached>0)return cached;
        long block=position/CHUNK;Job job=submit(block,0);
        if(job==null)throw new IOException("Download queue unavailable");
        byte[] data;
        try{data=job.get(35,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Read interrupted",e);}catch(ExecutionException|CancellationException|TimeoutException e){throw new IOException("Download failed",e);}
        int at=(int)(position-block*CHUNK),n=Math.min(count,data.length-at);System.arraycopy(data,at,out,offset,n);return n;
    }
    private Job submit(long block,int priority){
        synchronized(jobs){
            if(closed)return null;Job existing=jobs.get(block);
            if(existing!=null){if(priority<existing.priority&&!existing.started&&workers.remove(existing)){existing.priority=priority;workers.execute(existing);}return existing;}
            if(jobs.size()>=MAX_JOBS){
                if(priority>0)return null;
                Job victim=null;for(Job job:jobs.values())if(!job.started&&job.priority>0){victim=job;break;}
                if(victim!=null){workers.remove(victim);victim.cancel(false);}
                // At most two foreground HTTP requests may temporarily exceed the prefill bound.
                if(jobs.size()>=MAX_JOBS+2)return null;
            }
            long version=generation;Job job=new Job(block,priority,version);jobs.put(block,job);
            try{workers.execute(job);}catch(RejectedExecutionException stopped){jobs.remove(block);return null;}return job;
        }
    }
    private byte[] fetch(long block,long version)throws IOException{
        if(closed)throw new IOException("Closed");long at=block*CHUNK;int length=(int)Math.min(CHUNK,size-at);byte[] data=new byte[length];
        int copied=0;while(copied<length){int n=cache.readIndexAt(at+copied,data,copied,length-copied);if(n<0)break;copied+=n;}
        if(copied<length){
            RangeReadAhead.Reader reader=local.get();
            if(reader==null){reader=factory.open();readers.add(reader);local.set(reader);if(closed){local.remove();readers.remove(reader);reader.close();throw new IOException("Closed");}}
            try{byte[] missing=copied==0?data:new byte[length-copied];reader.read(at+copied,missing,missing.length);if(copied>0)System.arraycopy(missing,0,data,copied,missing.length);}
            catch(IOException|RuntimeException e){local.remove();readers.remove(reader);try{reader.close();}catch(IOException ignored){}throw e;}
        }
        if(!closed&&version==generation)try{cache.writeAt(at,data,0,length);}catch(IOException failure){cache.close();throw failure;}
        return data;
    }
    private final class Job extends FutureTask<byte[]> implements Comparable<Job>{
        final long block,order=sequence.incrementAndGet();volatile int priority;volatile boolean started;
        Job(long block,int priority,long version){super(()->fetch(block,version));this.block=block;this.priority=priority;}
        @Override public void run(){started=true;super.run();}
        @Override public int compareTo(Job other){int p=Integer.compare(priority,other.priority);return p!=0?p:Long.compare(order,other.order);}
        @Override protected void done(){
            if(!isCancelled())try{get();failures=0;retryAt=0;}catch(Exception e){retryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(4000,250L*(1L<<Math.min(4,++failures))));}
            synchronized(jobs){if(jobs.get(block)==this)jobs.remove(block);}wake();
        }
    }
    private void wake(){if(closed||!pumpQueued.compareAndSet(false,true))return;try{pump.execute(()->{pumpQueued.set(false);prefill();});}catch(RejectedExecutionException e){pumpQueued.set(false);}}
    private void prefill(){
        if(closed)return;if(!cache.isOpen()){close();return;}
        if(!anchored||System.nanoTime()<retryAt)return;
        long ahead=bufferedAhead();if(!timeControlled){if(ahead>=high)byteRefill=false;else if(ahead<low)byteRefill=true;}
        if(!fill||!timeControlled&&!byteRefill)return;
        long limit=Math.min(size,anchor+high),next=anchor/CHUNK*CHUNK;
        while(next<limit&&!closed){
            long cached=cache.contiguousEnd(next);
            if(cached>next){if(cached>=limit)break;next=cached/CHUNK*CHUNK;if(cached%CHUNK==0)continue;}
            if(submit(next/CHUNK,1)==null)break;
            next+=CHUNK;
            synchronized(jobs){if(jobs.size()>=MAX_JOBS)break;}
        }
    }
    @Override public void close(){
        if(closed)return;closed=true;pump.shutdownNow();
        synchronized(jobs){for(Job job:new ArrayList<>(jobs.values()))job.cancel(false);jobs.clear();}
        workers.shutdownNow();for(RangeReadAhead.Reader reader:readers)try{reader.close();}catch(IOException ignored){}readers.clear();
    }
}
