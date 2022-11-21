package org.solinger.sdbm;

import java.io.*;
import java.util.*;
import java.text.ParseException;

import junit.framework.*;

public class PageTest extends TestCase {

    private static Random rand = new Random();
    
    public PageTest(String name) {
	super(name);
    }

    public void testNoRoom() {
	Page p = new Page(1024);
	for (int i=0;i<1024;i++) {
	    try {
		p.put((""+i).getBytes(),(""+i).getBytes());
	    } catch (IllegalStateException e) {
		// cool
		return;
	    }
	}
	fail("Should have thrown IllegalStateException");
    }

    public void testSize() {
	Page p = new Page(1024);
	assert(p.isEmpty());

	p.put("foo".getBytes(),"bar".getBytes());
	assert(!p.isEmpty());
	assertEquals(1,p.size());

	p.put("foo".getBytes(),"baz".getBytes());
	assert(!p.isEmpty());
	assertEquals(1,p.size());

	p.put("goon".getBytes(),"baz".getBytes());
	assert(!p.isEmpty());
	assertEquals(2,p.size());

	p.put("foom".getBytes(),"afsdf".getBytes());
	assert(!p.isEmpty());
	assertEquals(3,p.size());

	p.remove("foo".getBytes());
	assert(!p.isEmpty());
	assertEquals(2,p.size());

	p.remove("foom".getBytes());
	assert(!p.isEmpty());
	assertEquals(1,p.size());

	p.remove("goon".getBytes());
	assert(p.isEmpty());
	assertEquals(0,p.size());
    }

    public void testContainsKey() {
	Page p = fillPage(1024);

	for (Enumeration en=p.keys();en.hasMoreElements();) {
	    byte[] key = (byte[]) en.nextElement();
	    assert(p.containsKey(key));
	}

	p = new Page(1024);
	assert(!p.containsKey("foo".getBytes()));
	p.put("foo".getBytes(),"bar".getBytes());
	assert(p.containsKey("foo".getBytes()));
	p.remove("foo".getBytes());
	assert(!p.containsKey("foo".getBytes()));
    }	

    public void testEnumerator() {
	Page p = fillPage(1024);
	p.print();
	HashMap map = new HashMap();

	for (int i=0;i<p.size();i++) {
	    map.put(new String(p.getKeyAt(i)),new String(p.getElementAt(i)));
	}

	for (Enumeration en=p.keys();en.hasMoreElements();) {
	    byte[] key = (byte[]) en.nextElement();
	    byte[] val = p.get(key);
	    if (!map.remove(new String(key)).equals(new String(val))) {
		fail("incorrect val between enum and getElementAt");
	    }
	}
	if (!map.isEmpty()) {
	    fail("Map should be empty");
	}
    }


    public void testGetStuffAt() {
	Page p = fillPage(1024);
	HashMap map = new HashMap();
	for (Enumeration en=p.keys();en.hasMoreElements();) {
	    byte[] key = (byte[]) en.nextElement();
	    map.put(new String(key),new String(p.get(key)));
	}

	for (int i=0;i<p.size();i++) {
	    String key = new String(p.getKeyAt(i));
	    String val = new String(p.getElementAt(i));
	    if (!map.remove(key).equals(val)) {
		fail("incorrect val between enum and getElementAt");
	    }
	}
	if (!map.isEmpty()) {
	    fail("Map should be empty");
	}
    }

    public void testSmallPage() {
	
    }

    public Page fillPage(int pageSize) {
	Page p = new Page(pageSize);
	for (int i=0;;i++) {
	    byte[] key = (""+i).getBytes();
	    byte[] val = (""+i).getBytes();
	    if (p.hasRoom(key.length+val.length)) {
		p.put(key,val);
	    } else {
		break;
	    }
	}
	return p;
    }

    public void testClone() {
	Page p = fillPage(1024);
	assert(p.equals(p.clone()));
    }
}
