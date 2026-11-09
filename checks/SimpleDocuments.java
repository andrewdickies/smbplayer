package com.smbplayer.checks;
import android.provider.*;
import android.database.*;
import android.os.*;
import java.io.*;

/** Read-only synthetic SAF tree, registered only in the temporary test build. */
public final class SimpleDocuments extends DocumentsProvider {
    private static final String[] C={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_FLAGS};
    public boolean onCreate(){return true;}
    public boolean isChildDocument(String parent,String child){return child.startsWith(parent+"/");}
    private void add(MatrixCursor c,String id){boolean directory=!id.contains("/file-");String name=id.equals("one")?"Folder One":id.equals("two")?"Folder Two":directory?"Child Folder":"Episode "+id.substring(id.lastIndexOf('-')+1)+".mp4";Object[] values={id,name,directory?DocumentsContract.Document.MIME_TYPE_DIR:"video/mp4",directory?0L:2097152L,1794000000000L,0};MatrixCursor.RowBuilder row=c.newRow();for(String col:c.getColumnNames()){int index=-1;for(int i=0;i<C.length;i++)if(C[i].equals(col))index=i;row.add(index<0?null:values[index]);}}
    public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS});}
    public Cursor queryDocument(String id,String[] projection){MatrixCursor c=new MatrixCursor(projection==null?C:projection);add(c,id);return c;}
    public Cursor queryChildDocuments(String parent,String[] projection,String sort){MatrixCursor c=new MatrixCursor(projection==null?C:projection);if(!parent.contains("/child"))add(c,parent+"/child");for(int i=1;i<=12;i++)add(c,parent+"/file-"+String.format(java.util.Locale.ROOT,"%02d",i));return c;}
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(new File(getContext().getFilesDir(),"dashboard-fixture.mp4"),ParcelFileDescriptor.MODE_READ_ONLY);}
}
