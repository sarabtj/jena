/*
 * (c) Copyright 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 * [See end of file]
 */

package com.hp.hpl.jena.sparql.mgt;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.query.ARQ;
import com.hp.hpl.jena.sparql.ARQException;

public class ARQMgt
{
    static private Logger log = LoggerFactory.getLogger(ARQMgt.class) ;
    private static boolean initialized = false ;
    private static Map<ObjectName, Object> mgtObjects = new HashMap<ObjectName, Object>() ;
    
    public static synchronized void init()
    {
        if ( initialized )
            return ;
        initialized = true ;
        
        String NS = ARQ.PATH ;
        
        add(NS+".system:type=ARQ", new ARQInfo()) ;
        
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer(); 
        
        //add("com.example:type=Hello", new Hello()) ;
        
        
        
//        Map<String, Object> mgtObjects = new HashMap<String, Object>() ;
//        mgtObjects.put("com.example:type=Hello", value)
//        
//        ObjectName name = new ObjectName("com.example:type=Hello"); 
//        Hello mbean = new Hello();
        for ( Entry<ObjectName, Object> entry : mgtObjects.entrySet() )
        {
            try {
                log.debug("Register MBean: "+entry.getKey()) ;
                mbs.registerMBean(entry.getValue(), entry.getKey());
            } catch (NotCompliantMBeanException ex)
            {
                throw new ARQException("Failed to register '"+entry.getKey().getCanonicalName()+"': "+ex.getMessage(), ex) ;
            } catch (InstanceAlreadyExistsException ex)
            {
                throw new ARQException("Failed to register '"+entry.getKey().getCanonicalName()+"': "+ex.getMessage(), ex) ;
            } catch (MBeanRegistrationException ex)
            {
                throw new ARQException("Failed to register '"+entry.getKey().getCanonicalName()+"': "+ex.getMessage(), ex) ;
            }
        }
    }
    
    private static void add(String name, Object object)
    {
        try
        {
            mgtObjects.put(new ObjectName(name), object) ;
        } catch (MalformedObjectNameException ex)
        {
            throw new ARQException("Failed to create name '"+name+"': "+ex.getMessage(), ex) ;
        }
    }
}

/*
 * (c) Copyright 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */