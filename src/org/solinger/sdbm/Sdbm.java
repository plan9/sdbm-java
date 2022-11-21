package org.solinger.sdbm;

import java.io.*;
import java.util.*;

/**
 * Java rewrite of sdbm.
 * sdbm - ndbm work-alike hashed database library
 * based on Per-Aake Larson's Dynamic Hashing algorithms. BIT 18 (1978).
 * original author: oz@nexus.yorku.ca
 * status: public domain.
 *
 * @author Justin F. Chapweske <justin@chapweske.com>
 * @version .01 06/06/98
 *
 * core routines
 */

public class Sdbm {

    public static final int DBLKSIZ = 4096;
    public static final int PBLKSIZ = 1024;
    public static final int PAIRMAX = 1008;   //arbitrary on PBLKSIZ-N
    public static final int SPLTMAX = 10;	    //maximum allowed splits

    public static final int SHORTSIZ = 2;
    public static final int BITSINBYTE = 8;

    public static final String DIREXT = ".dir";
    public static final String PAGEXT = ".pag";


    RandomAccessFile dirRaf;   // directory file descriptor
    RandomAccessFile pagRaf;   // page file descriptor
    File dirFile, pagFile;
    String mode;
    int maxbno;	             // size of dirfile in bits
    int curbit;	             // current bit number
    int hmask;		     // current hash mask
    Page page;               // page file block buffer
    int dirbno;	             // current block in dirbuf
    byte[] dirbuf;           // directory file block buffer
    int elementCount;        // The number of elements.
    Random rand = null;

    /**
     * @param name The name of the database, a name.pag and a name.dir file will be created.
     * @param mode The mode to open the database in, either "r" or "rw"
     */
    public Sdbm(File baseDir, String name, String mode) throws IOException {
	this.mode = mode;

	this.dirFile = new File(baseDir,name+DIREXT);
	this.pagFile = new File(baseDir,name+PAGEXT);

	dirRaf = new RandomAccessFile(dirFile,mode);
	pagRaf = new RandomAccessFile(pagFile,mode);
	dirbuf = new byte[DBLKSIZ];

	// need the dirfile size to establish max bit number.
	// zero size: either a fresh database, or one with a single,
	// unsplit data page: dirpage is all zeros.
	dirbno = dirRaf.length() == 0 ? 0 : -1;
	maxbno = (int) dirRaf.length() * BITSINBYTE;
	//System.out.println("MAXBNO:"+maxbno);
	//System.out.println("BITSINBYTE:"+BITSINBYTE);
	//System.out.println("size:"+dirRaf.length());

	for (Enumeration en = pages();en.hasMoreElements();) {
	    Page p = (Page) en.nextElement();
	    if (page == null) {
		page = p;
	    }
	    elementCount += p.size();
	}
	//System.out.println("Elements:"+elementCount);
    }

    
    private static final void checkKey(String key) {
	if (key == null) {
	    throw new NullPointerException();
	} else if (key.length() <= 0) {
	    throw new IllegalArgumentException("key too small: "+key.length());
	}
    }

    private static final int OFF_PAG(int off) {
	return off * PBLKSIZ;
    }

    private static final int OFF_DIR(int off) {
	return off * DBLKSIZ;
    }

    private static final int masks[] = {
	000000000000, 000000000001, 000000000003, 000000000007,
	000000000017, 000000000037, 000000000077, 000000000177,
	000000000377, 000000000777, 000000001777, 000000003777,
	000000007777, 000000017777, 000000037777, 000000077777,
	000000177777, 000000377777, 000000777777, 000001777777,
	000003777777, 000007777777, 000017777777, 000037777777,
	000077777777, 000177777777, 000377777777, 000777777777,
	001777777777, 003777777777, 007777777777, 017777777777
    };

    /**
     * Close the database.
     */
    public synchronized void close() throws IOException {
	dirRaf.close();
	pagRaf.close();
    }

    /**
     * Get the value associated with the key, returns null if that
     * value doesn't exist.
     */
    public synchronized String get(String key) throws IOException {
	checkKey(key);

	byte[] keyBytes = key.getBytes();

	//System.out.println(key);
	page = getPage(Hash.hash(keyBytes));
	//System.out.println(page.bno);
	//page.print();
	if (page == null) {
	    return null;
	}
	byte[] b = page.get(keyBytes);
 	if (b == null) {
	    return null;
	}
	return new String(b);
    }

    /**
     * @param key the key to check.
     *
     * @return true if the dbm contains the key
     */
    public synchronized boolean containsKey(String key) throws IOException {
	checkKey(key);

	byte[] keyBytes = key.getBytes();
	page = getPage(Hash.hash(keyBytes));

	if (page == null) {
	    return false;
	}
	return page.containsKey(keyBytes);
    }


    /**
     * Clear the database of all entries.
     */
    public synchronized void clear() throws IOException {
	if (!mode.equals("rw")) {
	    throw new IOException("This file is opened Read only");
	}

	dirRaf.close();
	pagRaf.close();

	try {
	    if (!dirFile.delete()) {
		throw new IOException("Unable to delete :"+dirFile);

	    }
	} finally {
	    if (!pagFile.delete()) {
		throw new IOException("Unable to delete :"+pagFile);
	    }
	}
	
	dirRaf = new RandomAccessFile(dirFile,mode);
	pagRaf = new RandomAccessFile(pagFile,mode);
	// zero the dirbuf
	dirbuf = new byte[DBLKSIZ];

	curbit = 0;
	hmask = 0;	
	page = null;  

	// need the dirfile size to establish max bit number.
	// zero size: either a fresh database, or one with a single,
	// unsplit data page: dirpage is all zeros.
	dirbno = dirRaf.length() == 0 ? 0 : -1;
	maxbno = (int) dirRaf.length() * BITSINBYTE;
	elementCount = 0;
    }

    /**
     * Cleanes the dbm by reinserting the keys/values into a fresh copy.
     * This can reduce the size of the dbm and speed up many operations if
     * the database has become sparse due to a large number of removals.
     */
    public synchronized void clean() throws IOException {
	if (!mode.equals("rw")) {
	    throw new IOException("This file is opened Read only");
	}

	if (rand == null) {
	    rand = new Random();
	}

	// FIX use createTempFile instead.
	String name = "sdbmtmp"+rand.nextInt(Integer.MAX_VALUE);
	
	Sdbm tmp = new Sdbm(dirFile.getAbsoluteFile().getParentFile(),
			    name,"rw");

	// Use a page enumerator to ensure that the elementCount is accurate,
	// considering that some pages may contain stale/invalid data.
	for (Enumeration en = pages();en.hasMoreElements();) {
	    Page p = (Page) en.nextElement();
	    if (page == null) {
		page = p;
	    }
	    for (int i=0;i<p.size();i++) {
		String key = new String(p.getKeyAt(i));
		String value = new String(p.getElementAt(i));
		if (key != null && value != null) {
		    tmp.put(key,value);
		}
	    }
	}

	tmp.close();
       
	dirRaf.close();
	pagRaf.close();


	dirFile.delete();
	pagFile.delete();

	tmp.dirFile.renameTo(dirFile);
	tmp.pagFile.renameTo(pagFile);

	dirRaf = new RandomAccessFile(dirFile,mode);
	pagRaf = new RandomAccessFile(pagFile,mode);
	//zero the dirbuf
	dirbuf = new byte[DBLKSIZ];

	// need the dirfile size to establish max bit number.
	// zero size: either a fresh database, or one with a single,
	// unsplit data page: dirpage is all zeros.
	dirbno = dirRaf.length() == 0 ? 0 : -1;
	maxbno = (int) dirRaf.length() * BITSINBYTE;	

	// re-count the elements because there may have been duplicate keys
	// in the old dbm.
	elementCount = 0;
	for (Enumeration en = pages();en.hasMoreElements();) {
	    Page p = (Page) en.nextElement();
	    if (page == null) {
		page = p;
	    }
	    elementCount += p.size();
	}
    }

    /**
     * removes the value associated with the key
     * @returns the removed value, null if it didn't exist.
     */
    public synchronized String remove(String key) throws IOException{
	checkKey(key);

	byte[] keyBytes = key.getBytes();
	page = getPage(Hash.hash(keyBytes));
	if (page == null) {
	    return null;
	}

	int n = page.size();
	byte[] removeBytes = page.remove(keyBytes);
	String val = null;
	if (removeBytes != null) {
	    val = new String(removeBytes);
	}

	if (page.size() < n) {
	    elementCount--;
	}

	// update the page file
	pagRaf.seek(OFF_PAG(page.bno));
	pagRaf.write(page.pag,0, PBLKSIZ);

	return val;
    }

    /**
     * puts the value into the database using key as its key.
     * @returns the old value of the key.
     */
    public synchronized String put(String key, String value) 
	throws IOException, SdbmException {
	
	checkKey(key);

	byte[] keyBytes = key.getBytes();

	int need = key.length() + value.length();
	// is the pair too big for this database ??
	if (need > PAIRMAX) {
	    throw new SdbmException("Pair is too big for this database");
	}

	int hash = Hash.hash(keyBytes);

	page = getPage(hash);

	// if we need to replace, delete the key/data pair
	// first. If it is not there, ignore.
	int n = page.size();
	byte[] valBytes = page.remove(keyBytes);
	String val = null;
	if (valBytes != null) {
	    val = new String(valBytes);
	}

	if (page.size() < n) {
	    elementCount--;
	}

	// if we do not have enough room, we have to split.
	if (!page.hasRoom(need)) {
	    makeRoom(hash, need);
	}

	// we have enough room or split is successful. insert the key,
	// and update the page file.

	page.put(keyBytes, value.getBytes());

	elementCount++;
	//	page.print();

	pagRaf.seek(OFF_PAG(page.bno));
	pagRaf.write(page.pag,0, PBLKSIZ);

	return val;    
    }

    /**
     * @returns true if it is empty, false otherwise.
     */
    public synchronized boolean isEmpty() {
	return elementCount <= 0;
    }

    /**
     * @returns the number of elements in the database.
     */
    public synchronized int size() {
	return elementCount;
    }

    /**
     * @returns a random key from the dbm, null if empty.
     */
    public synchronized String randomKey() throws IOException {
	Iterator it = randomKeys(1);
	return it.hasNext() ? (String) it.next() : null;
    }


    /**
     * @param n The number of desired keys.
     * @returns a number of random keys, up to n
     */
    public synchronized Iterator randomKeys(int n) throws IOException {
	HashSet keys = new HashSet();
	
	if (rand == null) {
	    rand = new Random();
	}

	// There is a chance that the dbm may be dirty and that there is
	// a misscount on the number of keys, or it is very sparse and thus
	// difficult to find random keys.  If this counter goes above N, 
	// then clean the database before continueing.
	int i=0;
	
	while (keys.size() < size() && keys.size() < n) {
    
	    // The pages should be relatively balanced, so if we choose
	    // a random value in a random page we should have a decent
	    // distribution.
	    
	    Page p = null;
	    do {
		// This dbm is not in good shape, clean it up.
		// This takes a long time, so don't do it often.
		if (i != 0 && i == 2*Math.min(n,size())) {
		    // FIX this should be augmented with a 'modified' flag
		    // to avoid redundant cleans.
		    clean();
		}
		
		i++;
		
		// Use rand to choose a random hash.
		p = getPage(rand.nextInt());
	    } while (p.size() == 0);
	    
	    keys.add(new String(p.getKeyAt(rand.nextInt(p.size()))));
	    
	}

	System.out.println("Took "+i+" iterations to find keys");

	return keys.iterator();
    }

    /**
     * makroom - make room by splitting the overfull page
     * this routine will attempt to make room for SPLTMAX times before
     * giving up.
     */
    private synchronized void makeRoom(int hash, int need) throws IOException, 
	SdbmException {
	
	Page newPage;

	int smax = SPLTMAX;
	do {

	    // Very important, don't want to write over newPage on loop.
	    newPage = new Page(PBLKSIZ);
	    // split the current page
	    page.split(newPage, hmask + 1);

	    // address of the new page
	    newPage.bno = (hash & hmask) | (hmask + 1);

	    // write delay, read avoidence/cache shuffle:
	    // select the page for incoming pair: if key is to go to the new
	    // page, write out the previous one, and copy the new one over,
	    // thus making it the current page. If not, simply write the new
	    // page, and we are still looking at the page of interest. current
	    // page is not updated here, as put will do so, after it inserts
	    // the incoming pair.
	    if ((hash & (hmask + 1)) != 0) {
		pagRaf.seek(OFF_PAG(page.bno));
		pagRaf.write(page.pag,0, PBLKSIZ);
		page = newPage;
	    } else {
		pagRaf.seek(OFF_PAG(newPage.bno));
		pagRaf.write(newPage.pag,0, PBLKSIZ);
	    }

	    setdbit(curbit);

	    // see if we have enough room now
	    if (page.hasRoom(need))
		return;

	    // try again... update curbit and hmask as getpage would have
	    // done. because of our update of the current page, we do not
	    // need to read in anything. BUT we have to write the current
	    // [deferred] page out, as the window of failure is too great.
	    curbit = 2 * curbit + ((hash & (hmask + 1)) != 0 ? 2 : 1);
	    hmask |= hmask + 1;

	    pagRaf.seek(OFF_PAG(page.bno));
	    pagRaf.write(page.pag,0, PBLKSIZ);

	} while (--smax != 0);

	// if we are here, this is real bad news. After SPLTMAX splits,
	// we still cannot fit the key. say goodnight.
	throw new SdbmException("AIEEEE! Cannot insert after SPLTMAX attempts");
    }

    /**
     * returns an enumeration of the pages in the database.
     */
    private Enumeration pages() {
	return new PageEnumerator();
    }

    private class PageEnumerator implements Enumeration {
	int blkptr;
	PageEnumerator() {
	}

	public boolean hasMoreElements() {
	    synchronized (Sdbm.this) {
		//If we're at the end of the file.
		try {
		    if (OFF_PAG(blkptr) >= pagRaf.length()) {
			return false;
		    }
		    
		} catch (IOException e) {
		    return false;
		}
		return true;
	    }
	}

	public Object nextElement() {
	    synchronized (Sdbm.this) {
		if (!hasMoreElements()) {
		    throw new NoSuchElementException("PageEnumerator");
		}
		Page p = new Page(PBLKSIZ);
		if (page == null || page.bno != blkptr) {
		    try {
			pagRaf.seek(OFF_PAG(blkptr));
			readLots(pagRaf, p.pag, 0, PBLKSIZ);
		    } catch (IOException e) {
			throw new NoSuchElementException(e.getMessage());
		    }
		} else {
		    p = page;
		}
		
		if (!p.isValid() || p == null)
		    throw new NoSuchElementException("PageEnumerator");
		blkptr++;
		return p;
	    }
	}
    }

    /**
     * returns an enumeration of the keys in the database.
     */
    public synchronized Enumeration keys() {
	return new Enumerator(true);
    }

    /**
     * returns an enumeration of the elements in the database.
     */
    public synchronized Enumeration elements() {
	return new Enumerator(false) ;
    }

    private class Enumerator implements Enumeration {
	boolean key;
	Enumeration penum;
	Page p;
	Enumeration enum;
	String next;

	Enumerator(boolean key) {
	    this.key = key;
	    penum = pages();
	    if (penum.hasMoreElements()) {
		p = (Page) penum.nextElement();
		enum = key ? p.keys() : p.elements();
		next = getNext();
	    } else {
		next = null;
	    }
	}

	public boolean hasMoreElements() {
	    synchronized (Sdbm.this) {
		return next != null;
	    }
	}

	private String getNext() {
	    for (;;) {
		if (!(penum.hasMoreElements() || enum.hasMoreElements())) {
		    return null;
		}
		if (enum.hasMoreElements()) {
		    byte[] b = (byte[]) enum.nextElement();
		    if (b != null) {
			return new String(b);
		    }
		} else if (penum.hasMoreElements()) {
		    p = (Page) penum.nextElement();
		    enum = key ? p.keys() : p.elements();
		}
	    }
	}

	public Object nextElement() {
	    synchronized (Sdbm.this) {
		String s = next;
		if (s == null) {
		    throw new NoSuchElementException("Enumerator");
		}
		next = getNext();
		return s;
	    }
	}
    }

    /**
     * all important binary tree traversal
     */
    protected synchronized Page getPage(int hash) throws IOException {
	int hbit = 0;
	int dbit = 0;
	int pagb;
	Page newPage;
	//System.out.println("maxbno:"+maxbno);
	//System.out.println("hash:"+hash);

	while (dbit < maxbno && getdbit(dbit) != 0) {
	    dbit = 2 * dbit + ((hash & (1 << hbit++)) != 0 ? 2 : 1);
	}

	//System.out.println("dbit: "+dbit+"...");

	curbit = dbit;
	hmask = masks[hbit];

	pagb = hash & hmask;

	//System.out.println("pagb: "+pagb);
	// see if the block we need is already in memory.
	// note: this lookaside cache has about 10% hit rate.
	if (page == null || pagb != page.bno) {

	    // note: here, we assume a "hole" is read as 0s.
	    // if not, must zero pagbuf first.
	    pagRaf.seek(OFF_PAG(pagb));
	    byte[] b = new byte[PBLKSIZ];
	    readLots(pagRaf, b, 0, PBLKSIZ);
	    newPage = new Page(b);

	    if (!newPage.isValid()) {
		// FIX maybe there is a better way to deal with corruption?
		// Corrupt page, return an empty one.
		b = new byte[PBLKSIZ];
		newPage = new Page(b);
	    }

	    newPage.bno = pagb;

	    //System.out.println("pag read: "+pagb);
	} else {
	    newPage = page;
	}

	return newPage;
    }

    protected synchronized int getdbit(int dbit) throws IOException {
	int c;
	int dirb;

	c = dbit / BITSINBYTE;
	dirb = c / DBLKSIZ;

	if (dirb != dirbno) {
	    dirRaf.seek(OFF_DIR(dirb));
	    readLots(dirRaf ,dirbuf,0, DBLKSIZ);

	    dirbno = dirb;

	    //System.out.println("dir read: "+dirb);
	}

	return dirbuf[c % DBLKSIZ] & (1 << dbit % BITSINBYTE);
    }

    protected synchronized void setdbit(int dbit) throws IOException {
	int c = dbit / BITSINBYTE;
	int dirb = c / DBLKSIZ;
	
	if (dirb != dirbno) {
	    clearByteArray(dirbuf);
	    dirRaf.seek(OFF_DIR(dirb));
	    readLots(dirRaf,dirbuf,0, DBLKSIZ);
	    
	    dirbno = dirb;
	    
	    //System.out.println("dir read: "+dirb);
	}

	dirbuf[c % DBLKSIZ] |= (1 << dbit % BITSINBYTE);

	if (dbit >= maxbno)
	    maxbno += DBLKSIZ * BITSINBYTE;


	dirRaf.seek(OFF_DIR(dirb));
	dirRaf.write(dirbuf,0, DBLKSIZ);

    }

    public static void clearByteArray(byte[] arr) {
        for (int i=0;i<arr.length;i++) {
	    arr[i] = 0;
	}
    }
    
    public static void readLots(RandomAccessFile f, byte[] b, int off, 
				int len) throws IOException {
	int n = 0;
        while (n < len) {
            int count = f.read(b, off+n, len-n);
            n += count;
            if (count < 0) {
		break;
	    }
        }
    }

    public void print() throws IOException {
	System.out.print("[");
	for (Enumeration en=keys();en.hasMoreElements();) {
	    String key = (String) en.nextElement();
	    System.out.print(key+"="+get(key));
	    if (en.hasMoreElements()) {
		System.out.print(",");
	    }
	}
	System.out.println("]");
    }

    public static void main(String[] argv) throws Exception {

	Sdbm db = new Sdbm(null,"testdb","rw");
	BufferedReader br = new BufferedReader(new InputStreamReader
					       (System.in));
	boolean mode = true;
	while (true) {
	    //db.print();
	    System.out.println("<elementCount="+db.size()+">");
	    String s = br.readLine();
	    if (s.charAt(0) == 'p') {
		System.out.println(db.put(s.substring(2,s.indexOf('=',2)),
					  s.substring(s.indexOf('=',2)+1)));
	    } else if (s.charAt(0) == 'g') {
		System.out.println(db.get(s.substring(2)));
	    } else if (s.charAt(0) == 'r') {
		System.out.println(db.remove(s.substring(2)));
	    } else if (s.charAt(0) == 'z') {
		int n = Integer.parseInt(s.substring(2));
		Random r = new Random();
		for (int i=0;i<n;i++) {
		    db.put(""+r.nextInt(10000),""+r.nextInt(1000));
		}
	    } else if (s.charAt(0) == 'y') {
		int n = Integer.parseInt(s.substring(2));
		Random r = new Random();
		for (int i=0;i<n;i++) {
		    db.remove(""+r.nextInt(10000));
		}
	    } else if (s.charAt(0) == 'q') {
		int n = Integer.parseInt(s.substring(2));

		System.out.print("<");
		for (Iterator it = db.randomKeys(n);it.hasNext();) {
		    
		    String key = (String) it.next();
		    System.out.print(key);
		    if (it.hasNext()) {
			System.out.print(",");
		    }
		}
		System.out.println(">");
	    } else if (s.charAt(0) == 'c') {
		db.clean();
	    }
	}

	    //System.out.println(db.get("http1"));


	     /*Enumeration enum = db.keys();
	     while (enum.hasMoreElements()) {
	     String key = (String) enum.nextElement();
	     //if (h.get(key) != null) {
	     System.out.print(key+"::");//+"\" : \""+db.get(key)+"\"");
	     System.out.println(db.get(key));
	     //System.out.println(db.size());
	     //}
	     //    h.put(key,"stuff");
	     }*/
	
    }
}
