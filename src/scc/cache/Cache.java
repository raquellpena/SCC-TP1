package scc.cache;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.servlet.ServletContext;
import java.util.Optional;

public class Cache {

	private static JedisPool instance;

	public synchronized static JedisPool getInstance() {
		if(!Boolean.parseBoolean(Optional.ofNullable(System.getenv("ENABLE_CACHE")).orElse("true")))
			return null;

		if(instance != null)
			return instance;

		final JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setMaxTotal(128);
		poolConfig.setMaxIdle(128);
		poolConfig.setMinIdle(16);
		poolConfig.setTestOnBorrow(true);
		poolConfig.setTestOnReturn(true);
		poolConfig.setTestWhileIdle(true);
		poolConfig.setNumTestsPerEvictionRun(3);
		poolConfig.setBlockWhenExhausted(true);
		instance = new JedisPool(poolConfig, System.getenv( "REDIS_URL"), 6380, 5000, System.getenv( "REDIS_KEY"), true);
		return instance;

	}

}
