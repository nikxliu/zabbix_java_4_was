# zabbix_java_4_was
zabbix java gateway for IBM WebSphere Application Server support.

This project base on zabbix_java_gateway 3.0

Only add some code on `JMXItemChecker.java`.

If you want use, you should get the three extra jars.

`com.ibm.jaxws.thinclient_9.0.jar`,`com.ibm.ws.admin.client_9.0.jar`,`com.ibm.ws.orb_9.0.jar`

Also, you should specify the cert store use the -D parameters like that:
`-Dtrust.path=[path]/trust.p12 -Dkey.path=[path]/key.p12`



This project is to extend the zabbix_java_gateway to support IBM WebSphere Application Server. It can visit the WAS's Mbean to get more detail pmi data to monitor the WAS status.

## Now, I only add two component to monitor.
1. ThreadPool

  - ActiveCount
  
    -- current    
    -- High Water
    
  - PoolSize
  
    -- current
    -- High Water
    
Template Exemple：

   `jmx["WebSphere:*,type=Perf",nodename.servername.ThreadPool.WebContainer.PoolSize.current]`
   `jmx["WebSphere:*,type=Perf",nodename.servername.ThreadPool.WebContainer.PoolSize.max]`
   
2. DataSource

  - FreePoolSize
  
    -- current    
    -- High Water
    
  - PoolSize
  
    -- current    
    -- High Water
    
  - PercentUsed
  
    -- current    
    -- High Water
    
Template Exemple：

  `jmx["WebSphere:*,type=Perf",nodename.servername.drivername.datasourcename.PoolSize.current]`
