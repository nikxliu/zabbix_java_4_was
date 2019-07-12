/*
** Zabbix
** Copyright (C) 2001-2019 Zabbix SIA
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
**/
/***
 * Add get the was pmi data by the perf mbean.
 * @nikx
 */

package com.zabbix.gateway;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;

import org.json.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminClientFactory;
import com.ibm.websphere.management.exception.ConnectorException;
import com.ibm.websphere.pmi.stat.StatDescriptor;
import com.ibm.websphere.pmi.stat.WSJDBCConnectionPoolStats;
import com.ibm.websphere.pmi.stat.WSRangeStatistic;
import com.ibm.websphere.pmi.stat.WSStats;
import com.ibm.websphere.pmi.stat.WSThreadPoolStats;

class JMXItemChecker extends ItemChecker {
	private static final Logger logger = LoggerFactory.getLogger(JMXItemChecker.class);

	private JMXServiceURL url;
	private JMXConnector jmxc;
	private MBeanServerConnection mbsc;

	private String username;
	private String password;

	// for IBM WAS
	private String product = "";
	private String washost = "";
	private String wasport = "";
	private String process = "";
	private String node = "";
	private String type = "";
	private AdminClient ac;

	public JMXItemChecker(JSONObject request) throws ZabbixException {
		super(request);

		try {
			String conn = request.getString(JSON_TAG_CONN);
			int port = request.getInt(JSON_TAG_PORT);
			washost = conn;
			wasport = String.valueOf(port);

			url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://[" + conn + "]:" + port + "/jmxrmi");
			jmxc = null;
			mbsc = null;

			username = request.optString(JSON_TAG_USERNAME, null);
			password = request.optString(JSON_TAG_PASSWORD, null);

			if (null != username && null == password || null == username && null != password)
				throw new IllegalArgumentException("invalid username and password nullness combination");

			if (request.toString().contains("WebSphere:*,type=Perf")) {
				product = "WAS";
			}
		} catch (Exception e) {
			throw new ZabbixException(e);
		}
	}

	public JSONArray getValuesCommon() throws ZabbixException {

		JSONArray values = new JSONArray();

		try {
			HashMap<String, String[]> env = null;

			if (null != username && null != password) {
				env = new HashMap<String, String[]>();
				env.put(JMXConnector.CREDENTIALS, new String[] { username, password });
			}

			jmxc = ZabbixJMXConnectorFactory.connect(url, env);
			mbsc = jmxc.getMBeanServerConnection();

			for (String key : keys)
				values.put(getJSONValue(key));
		} catch (Exception e) {
			throw new ZabbixException(e);
		} finally {
			try {
				if (null != jmxc)
					jmxc.close();
			} catch (java.io.IOException exception) {
			}

			jmxc = null;
			mbsc = null;
		}

		return values;
	}

	public JSONArray getValuesWAS() {

		JSONArray values = new JSONArray();

		Properties connectProps = new Properties();

		connectProps.setProperty(AdminClient.CONNECTOR_TYPE, AdminClient.CONNECTOR_TYPE_SOAP);
		connectProps.setProperty(AdminClient.CONNECTOR_HOST, washost);
		connectProps.setProperty(AdminClient.CONNECTOR_PORT, wasport);

		connectProps.setProperty(AdminClient.CONNECTOR_SECURITY_ENABLED, "true");
		connectProps.setProperty(AdminClient.CACHE_DISABLED, "false");
		connectProps.setProperty(AdminClient.USERNAME, username);
		connectProps.setProperty(AdminClient.PASSWORD, password);
		String trustPath = System.getProperty("trust.path");
		String keyPath = System.getProperty("key.path");

		connectProps.setProperty("javax.net.ssl.trustStore", trustPath);
		connectProps.setProperty("javax.net.ssl.keyStore", keyPath);
		connectProps.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
		connectProps.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
		connectProps.setProperty("javax.net.ssl.trustStorePassword", "WebAS");
		connectProps.setProperty("javax.net.ssl.keyStorePassword", "WebAS");

		try {
			ac = AdminClientFactory.createAdminClient(connectProps);
			for (String key : keys)
				values.put(getJSONValue(key));
		} catch (ConnectorException e) {
			logger.error(e.toString());
		}

		return values;
	}

	@Override
	public JSONArray getValues() throws ZabbixException {
		JSONArray values = null;

		if ("WAS".equals(product)) {
			values = getValuesWAS();
		} else {
			values = getValuesCommon();
		}

		return values;
	}

	protected String getStringValueCommon(String key) throws Exception {

		ZabbixItem item = new ZabbixItem(key);

		if (item.getKeyId().equals("jmx")) {
			if (2 != item.getArgumentCount())
				throw new ZabbixException("required key format: jmx[<object name>,<attribute name>]");

			ObjectName objectName = new ObjectName(item.getArgument(1));
			String attributeName = item.getArgument(2);
			String realAttributeName;
			String fieldNames = "";

			// Attribute name and composite data field names are separated by dots. On the
			// other hand the
			// name may contain a dot too. In this case user needs to escape it with a
			// backslash. Also the
			// backslash symbols in the name must be escaped. So a real separator is
			// unescaped dot and
			// separatorIndex() is used to locate it.

			int sep = HelperFunctionChest.separatorIndex(attributeName);

			if (-1 != sep) {
				logger.trace("'{}' contains composite data", attributeName);

				realAttributeName = attributeName.substring(0, sep);
				fieldNames = attributeName.substring(sep + 1);
			} else
				realAttributeName = attributeName;

			// unescape possible dots or backslashes that were escaped by user
			realAttributeName = HelperFunctionChest.unescapeUserInput(realAttributeName);

			logger.trace("attributeName:'{}'", realAttributeName);
			logger.trace("fieldNames:'{}'", fieldNames);

			return getPrimitiveAttributeValue(mbsc.getAttribute(objectName, realAttributeName), fieldNames);
		} else if (item.getKeyId().equals("jmx.discovery")) {
			if (0 != item.getArgumentCount())
				throw new ZabbixException("required key format: jmx.discovery");

			JSONArray counters = new JSONArray();

			for (ObjectName name : mbsc.queryNames(null, null)) {
				logger.trace("discovered object '{}'", name);

				for (MBeanAttributeInfo attrInfo : mbsc.getMBeanInfo(name).getAttributes()) {
					logger.trace("discovered attribute '{}'", attrInfo.getName());

					if (!attrInfo.isReadable()) {
						logger.trace("attribute not readable, skipping");
						continue;
					}

					try {
						logger.trace("looking for attributes of primitive types");
						String descr = (attrInfo.getName().equals(attrInfo.getDescription()) ? null
								: attrInfo.getDescription());
						findPrimitiveAttributes(counters, name, descr, attrInfo.getName(),
								mbsc.getAttribute(name, attrInfo.getName()));
					} catch (Exception e) {
						Object[] logInfo = { name, attrInfo.getName(), e };
						logger.trace("processing '{},{}' failed", logInfo);
					}
				}
			}

			JSONObject mapping = new JSONObject();
			mapping.put(ItemChecker.JSON_TAG_DATA, counters);
			return mapping.toString();
		} else
			throw new ZabbixException("key ID '%s' is not supported", item.getKeyId());

	}

	private String getStringValueWAS(String key) throws ZabbixException {
		ZabbixItem item = new ZabbixItem(key);
		String value = "";

		if (item.getKeyId().equals("jmx")) {
			if (2 != item.getArgumentCount())
				throw new ZabbixException("required key format: jmx[<object name>,<attribute name>]");

			try {

				String attributeName = item.getArgument(2);
				logger.debug("attributeName:'{}'" + attributeName);
				String[] attrNames = attributeName.split("\\.");

				if (attrNames == null) {
					throw new ZabbixException("attribute is not right.");
				} else {
					node = attrNames[0];
					process = attrNames[1];
					type = attrNames[2];
				}
				ObjectName firstQueryObj = new ObjectName(
						item.getArgument(1) + ",process=" + process + ",node=" + node);

				try {
					ObjectName queryResultObj = null;
					Set s = ac.queryNames(firstQueryObj, null);
					if (!s.isEmpty()) {
						queryResultObj = (ObjectName) s.iterator().next();
						switch (type) {
						case "ThreadPool":
							value = getThreadPool(queryResultObj, attrNames);
							break;
						case "JDBC":
							value = getJdbc(queryResultObj, attrNames);
							break;
						default:
							break;
						}	
					} else {
						logger.info("Perf MBean was not found");
					}
				} catch (ConnectorException e) {
					logger.error(e.toString());
				}

			} catch (MalformedObjectNameException e) {
				logger.error(e.toString());
			}

		}
		return value;
	}

	private String getJdbc(ObjectName queryResultObj, String[] attrNames) throws ZabbixException {
		String value = "";
		String jdbcDriverName = attrNames[3];
		String dsName = attrNames[4];
		String attribute = attrNames[5];
		String metrics = attrNames[6];
		WSStats wsStats;
		WSStats[] wsStatses = getJdbcStatsInfoBySd(queryResultObj, jdbcDriverName);
		WSRangeStatistic wsStatistic;
		for (int k = 0; k < wsStatses.length; k++) {
			WSStats jdbcStats = wsStatses[k];

			if (jdbcStats == null) {
				break;
			}
			WSStats[] dsStatses = jdbcStats.getSubStats();
			if (dsStatses == null) {
				break;
			}
			for (int m = 0; m < dsStatses.length; m++) {
				if (dsStatses[m].getName().equals(dsName)) {
					wsStats = dsStatses[m];
					switch (attribute) {
					case "FreePoolSize":
						wsStatistic = (WSRangeStatistic) wsStats.getStatistic(WSJDBCConnectionPoolStats.FreePoolSize);
						switch (metrics) {
						case "current":
							value = String.valueOf(wsStatistic.getCurrent());
							break;
						case "max":
							value = String.valueOf(wsStatistic.getHighWaterMark());
							break;
						default:
							value = "";
						}
						break;
					case "PoolSize":
						wsStatistic = (WSRangeStatistic) wsStats.getStatistic(WSJDBCConnectionPoolStats.PoolSize);
						switch (metrics) {
						case "current":
							value = String.valueOf(wsStatistic.getCurrent());
							break;
						case "max":
							value = String.valueOf(wsStatistic.getHighWaterMark());
							break;
						default:
							value = "";
						}
						break;
					case "PercentUsed":
						wsStatistic = (WSRangeStatistic) wsStats.getStatistic(WSJDBCConnectionPoolStats.PercentUsed);
						switch (metrics) {
						case "current":
							value = String.valueOf(wsStatistic.getCurrent());
							break;
						case "max":
							value = String.valueOf(wsStatistic.getHighWaterMark());
							break;
						default:
							value = "";
						}
						break;
					default:
						value = "-99999";
					}
					break;
				}
			}
		}
		return value;
	}

	private String getThreadPool(ObjectName queryResultObj, String[] attrNames) throws ZabbixException {
		String value = "";
		ObjectName threadPool = getThreadPoolMBean(node, process, attrNames[3]);
		WSStats wsStats = getStatsInfo(queryResultObj, threadPool);
		String attribute = attrNames[4];
		String metrics = attrNames[5];
		WSRangeStatistic wsStatistic;
		switch (attribute) {
		case "ActiveCount":
			wsStatistic = (WSRangeStatistic) wsStats.getStatistic(WSThreadPoolStats.ActiveCount);
			switch (metrics) {
			case "current":
				value = String.valueOf(wsStatistic.getCurrent());
				break;
			case "max":
				value = String.valueOf(wsStatistic.getHighWaterMark());
				break;
			default:
				value = "";
			}
			break;
		case "PoolSize":
			wsStatistic = (WSRangeStatistic) wsStats.getStatistic(WSThreadPoolStats.PoolSize);
			switch (metrics) {
			case "current":
				value = String.valueOf(wsStatistic.getCurrent());
				break;
			case "max":
				value = String.valueOf(wsStatistic.getHighWaterMark());
				break;
			default:
				value = "";
			}
			break;
		default:
			value = "-99999";
		}
		return value;
	}

	private ObjectName getThreadPoolMBean(String nodeName, String serverName, String threadPoolType) throws ZabbixException {
		ObjectName threadPool = null;
		// Query for the ObjectName of the WebContainer MBean on the given node and server
		try {
			String query = "WebSphere:*,type=ThreadPool,process=" + serverName + ",node=" + nodeName;
			ObjectName queryName = new ObjectName(query);
			Set s = ac.queryNames(queryName, null);
			if (!s.isEmpty()) {
				for (Iterator i = s.iterator(); i.hasNext();) {
					threadPool = (ObjectName) i.next();
					if (threadPool.getKeyProperty("name").toUpperCase().equals(threadPoolType.toUpperCase())) {
						break;
					}
				}
			} else {
				logger.info("WebContainer MBean was not found");
			}
		} catch (Exception e) {
			throw new ZabbixException(e);
		} 
		return threadPool;
	}

	private WSStats getStatsInfo(ObjectName perf, ObjectName objectName) throws ZabbixException {
		WSStats stats = null;
		boolean recursive = true;
		Object[] params = new Object[2];
		params[0] = objectName; // either ObjectName or or MBeanStatDescriptor
		params[1] = Boolean.valueOf(recursive);
		String[] signature = new String[] { "javax.management.ObjectName", "java.lang.Boolean" };
		try {
			stats = (WSStats) ac.invoke(perf, "getStatsObject", params, signature);
			if (stats == null) {
				logger.debug("Did not get the stats of " + objectName.getKeyProperty("name"));
			}
		} catch (Exception e) {
			throw new ZabbixException(e);
		} 
		return stats;
	}

	private WSStats[] getJdbcStatsInfoBySd(ObjectName perf, String objectName) throws ZabbixException {
		WSStats[] stats = null;
		boolean recursive = true;
		Object[] params = new Object[2];
		StatDescriptor sd = new StatDescriptor(new String[] { WSJDBCConnectionPoolStats.NAME, objectName });
		params[0] = new StatDescriptor[] { sd }; // either ObjectName or or MBeanStatDescriptor
		params[1] = Boolean.valueOf(recursive);
		String[] signature = new String[] { "[Lcom.ibm.websphere.pmi.stat.StatDescriptor;", "java.lang.Boolean" };
		try {
			stats = (WSStats[]) ac.invoke(perf, "getStatsArray", params, signature);
			if (stats == null) {
				System.out.println("Did not get the stats of " + objectName);
				stats = new WSStats[] {};
			}
		} catch (Exception e) {
			throw new ZabbixException(e);
		}
			
		return stats;
	}

	@Override
	protected String getStringValue(String key) throws Exception {
		String value = "";
		if ("WAS".equals(product)) {
			value = getStringValueWAS(key);
		} else {
			value = getStringValueCommon(key);
		}
		return value;
	}

	private String getPrimitiveAttributeValue(Object dataObject, String fieldNames) throws ZabbixException {
		logger.trace("drilling down with data object '{}' and field names '{}'", dataObject, fieldNames);

		if (null == dataObject)
			throw new ZabbixException("data object is null");

		if (fieldNames.equals("")) {
			if (isPrimitiveAttributeType(dataObject.getClass()))
				return dataObject.toString();
			else
				throw new ZabbixException("data object type is not primitive: %s", dataObject.getClass());
		}

		if (dataObject instanceof CompositeData) {
			logger.trace("'{}' contains composite data", dataObject);

			CompositeData comp = (CompositeData) dataObject;

			String dataObjectName;
			String newFieldNames = "";

			int sep = HelperFunctionChest.separatorIndex(fieldNames);

			if (-1 != sep) {
				dataObjectName = fieldNames.substring(0, sep);
				newFieldNames = fieldNames.substring(sep + 1);
			} else
				dataObjectName = fieldNames;

			// unescape possible dots or backslashes that were escaped by user
			dataObjectName = HelperFunctionChest.unescapeUserInput(dataObjectName);

			return getPrimitiveAttributeValue(comp.get(dataObjectName), newFieldNames);
		} else
			throw new ZabbixException("unsupported data object type along the path: %s", dataObject.getClass());
	}

	private void findPrimitiveAttributes(JSONArray counters, ObjectName name, String descr, String attrPath,
			Object attribute) throws JSONException {
		logger.trace("drilling down with attribute path '{}'", attrPath);

		if (isPrimitiveAttributeType(attribute.getClass())) {
			logger.trace("found attribute of a primitive type: {}", attribute.getClass());

			JSONObject counter = new JSONObject();

			counter.put("{#JMXDESC}", null == descr ? name + "," + attrPath : descr);
			counter.put("{#JMXOBJ}", name);
			counter.put("{#JMXATTR}", attrPath);
			counter.put("{#JMXTYPE}", attribute.getClass().getName());
			counter.put("{#JMXVALUE}", attribute.toString());

			counters.put(counter);
		} else if (attribute instanceof CompositeData) {
			logger.trace("found attribute of a composite type: {}", attribute.getClass());

			CompositeData comp = (CompositeData) attribute;

			for (String key : comp.getCompositeType().keySet())
				findPrimitiveAttributes(counters, name, descr, attrPath + "." + key, comp.get(key));
		} else if (attribute instanceof TabularDataSupport || attribute.getClass().isArray()) {
			logger.trace("found attribute of a known, unsupported type: {}", attribute.getClass());
		} else
			logger.trace("found attribute of an unknown, unsupported type: {}", attribute.getClass());
	}

	private boolean isPrimitiveAttributeType(Class<?> clazz) {
		Class<?>[] clazzez = { Boolean.class, Character.class, Byte.class, Short.class, Integer.class, Long.class,
				Float.class, Double.class, String.class, java.math.BigDecimal.class, java.math.BigInteger.class,
				java.util.Date.class, javax.management.ObjectName.class };

		return HelperFunctionChest.arrayContains(clazzez, clazz);
	}
}
