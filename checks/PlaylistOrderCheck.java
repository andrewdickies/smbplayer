package com.smbplayer.tv;

import java.util.*;

public final class PlaylistOrderCheck {
    static final class Item {
        final String name; final long size, date;
        Item(String n, long d, long m) { name=n; size=d; date=m; }
    }
    static List<Item> order(List<Item> source,int mode,boolean reverse,long seed) {
        return PlaylistOrder.sort(source,mode,reverse,seed,i->i.size,i->i.date,i->i.name,i->i.name);
    }
    static void check(boolean b,String text) { if(!b)throw new AssertionError(text); }
    public static void main(String[] args) {
        Item a=new Item("Alpha",200,300),b=new Item("Beta",100,100),c=new Item("Gamma",0,0);
        List<Item> items=Arrays.asList(c,a,b);
        check(order(items,0,false,1).equals(Arrays.asList(b,a,c)),"File size ascending");
        check(order(items,0,true,1).equals(Arrays.asList(a,b,c)),"Unknown size must remain last descending");
        check(order(items,1,false,1).equals(Arrays.asList(a,b,c)),"Name ascending");
        check(order(items,1,true,1).equals(Arrays.asList(c,b,a)),"Name descending");
        check(order(items,2,false,1).equals(Arrays.asList(b,a,c)),"Date ascending");
        check(order(items,2,true,1).equals(Arrays.asList(a,b,c)),"Unknown date must remain last descending");
        Item tie=new Item("Omega",200,300);
        check(order(Arrays.asList(a,tie),0,true,1).equals(Arrays.asList(tie,a)),"Size ties must reverse name ordering too");
        Item large=new Item("Large",5L*1024*1024*1024,Long.MAX_VALUE);
        check(order(Arrays.asList(large,a),0,false,1).equals(Arrays.asList(a,large)),"File sizes above 32-bit range were truncated");
        List<Item> random=order(items,3,false,147);
        check(random.equals(order(Arrays.asList(b,c,a),3,false,147)),"Random order changed with source enumeration");
        Collections.reverse(random);check(random.equals(order(items,3,true,147)),"Random order direction must reverse exactly");
        check(items.equals(Arrays.asList(c,a,b)),"Source list mutated");
        System.out.println("File size, name, date, unknown metadata, stable random and direction checks passed");
    }
}
