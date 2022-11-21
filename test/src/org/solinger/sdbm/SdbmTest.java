package org.solinger.sdbm;

import java.io.File;

import java.util.Date;

import org.solinger.sdbm.Sdbm;

import junit.framework.*;

/**
 * Test Sdbm.
 */
public class SdbmTest extends TestCase {

    // keep track of how long the test took
    private long m_time;


    public SdbmTest(String name)
    {
	super(name);
    }

    public void testIt() throws Exception
    {
        Sdbm sdbm = new Sdbm(new File(System.getProperty("java.io.tmpdir")),
			     "filt","rw");
	startClock();
	
	int low = 1;
	int high = 500000;
	int n = 0;
	boolean found = false;
	int count = 0;
	int lastCount = -1;
	String pad = null;
	String key = null;
	String value = null;
	
	while (!found) {
	    
	    count = ((int)(high + low) / 2);
	    System.out.println("Current size: " + count + " low: " + low + " high: " + high);
	    boolean error = false;
	    sdbm.clear();
	    
	    
	    for (int i=0; i<count; i++) {
		pad = pad(i);
		key = "key" + pad;
		value = "val" + pad;
		sdbm.put(key, value);
	    }
	    
	    int j = 0;
	    while ( !error && j < count ) {
		
		pad = pad(j);
		key = "key" + pad;
		value = "val" + pad;
		error = (!value.equals(sdbm.get(key)));
		j++;
	    }
	    
	    
	    if ( error ) {
		System.out.println("ERROR: Lost value for key " + key 
				   + " at " + count + " entries");
		high = count-1;
		fail("ERROR: Lost value for key " + key 
		     + " at " + count + " entries");
	    } else {
		System.out.println("SUCCESS: No loss with  " + count + 
				   " entries");
		low = count+1;
	    }
	    
	    
	    found = Math.abs(count - lastCount) < 2;
	    lastCount = count;
	    n++;
	    
	}

	System.out.println("Stopped at: " + count + " in "
			   + (getTime()/1000) + " seconds and " + n
			   + " iterations");
	
	
    }


    private void startClock()
    {
        m_time = new Date().getTime();
    }

    private long getTime()
    {
        long now = new Date().getTime();
        return (now - m_time);
    }

    private String pad(int i)
    {
        if ( i > 999999 )
            return "" + i;
        if ( i > 99999 )
            return "0" + i;
        if ( i > 9999 )
            return "00" + i;
        if ( i > 999 )
            return "000" + i;
        if ( i > 99 )
            return "0000" + i;
        if ( i > 9 )
            return "00000" + i;
        return "000000" + i;
    }

}
