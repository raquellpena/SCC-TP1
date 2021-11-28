package scc.data.user;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.JedisPool;
import scc.data.authentication.Session;
import scc.cache.Cache;
import scc.mgt.AzureProperties;

import javax.servlet.ServletContext;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Cookie;
import java.util.List;
import java.util.stream.Collectors;

public class UsersDBLayer {
	private static final String DB_NAME = "scc2122db";
	
	private static UsersDBLayer instance;

	public static synchronized UsersDBLayer getInstance() {
		if(instance == null) {
			try {
				CosmosClient client = new CosmosClientBuilder()
						.endpoint(AzureProperties.getProperty("COSMOSDB_URL"))
						.key(AzureProperties.getProperty("COSMOSDB_KEY"))
						.gatewayMode()		// replace by .directMode() for better performance
						.consistencyLevel(ConsistencyLevel.SESSION)
						.connectionSharingAcrossClientsEnabled(true)
						.contentResponseOnWriteEnabled(true)
						.buildClient();
				JedisPool cache = Cache.getInstance();
				instance = new UsersDBLayer(client,cache);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return instance;
	}
	
	private final CosmosClient client;
	private final JedisPool cache;
	private CosmosContainer users;
	
	public UsersDBLayer(CosmosClient client, JedisPool cache) {
		this.client = client;
		this.cache = cache;
	}
	
	private synchronized void init() {
		if(users != null) return;
		CosmosDatabase db = client.getDatabase(DB_NAME);
		users = db.getContainer("Users");
	}

	public void discardUserById(String id) {
		init();
		UserDAO userDAO = getUserById(id);
		userDAO.setGarbage(true);
		updateUser(userDAO);
	}

	public boolean delUserById(String id) {
		init();
		cache.getResource().del("user: " + id);
		PartitionKey key = new PartitionKey(id);
		return users.deleteItem(id, key, new CosmosItemRequestOptions()).getStatusCode() < 400;
	}
	
	public boolean putUser(UserDAO user) {
		init();
		try {
			cache.getResource().set("user:" + user.getIdUser(), new ObjectMapper().writeValueAsString(user));
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		return users.createItem(user).getStatusCode() < 400;
	}
	
	public UserDAO getUserById( String id) {
		init();
		String res = cache.getResource().get("user:" + id);
		if (res != null) {
			try {
				return new ObjectMapper().readValue(res, UserDAO.class);
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			}
		}
		return users.queryItems("SELECT * FROM Users WHERE Users.idUser=\"" + id + "\"", new CosmosQueryRequestOptions(), UserDAO.class).stream().findFirst().orElse(null);
	}
	
	public UserDAO updateUser(UserDAO user) {
		init();

		try {
			cache.getResource().set("user:" + user.getIdUser(), new ObjectMapper().writeValueAsString(user));
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}

		return users.replaceItem(user, user.getIdUser(),new PartitionKey(user.getIdUser()), new CosmosItemRequestOptions()).getItem();
	}

	
	public List<UserDAO> getUsers(int off, int limit) {
		init();
		return users.queryItems("SELECT * FROM Users OFFSET "+off+" LIMIT "+limit, new CosmosQueryRequestOptions(), UserDAO.class).stream().collect(Collectors.toList());
	}

    public List<UserDAO> getDeletedUsers() {
		init();
		return users.queryItems("SELECT * FROM Users WHERE garbage = 1", new CosmosQueryRequestOptions(), UserDAO.class).stream().collect(Collectors.toList());
	}

	public void putSession(Session s) {
		init();

		try {
			cache.getResource().set(s.getSessionId(), new ObjectMapper().writeValueAsString(s));
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
	}

	public Session getSession(String sessionID) {
		try {
			return new ObjectMapper().readValue(cache.getResource().get(sessionID), Session.class);
		} catch (Exception e) {
			e.printStackTrace();
			throw new NotAuthorizedException("No valid session initialized");
		}
	}

	/**
	 * Throws exception if not appropriate user for operation on Channel
	 */
	public Session checkCookieUser(Cookie session, String id) throws NotAuthorizedException {
		Session s = checkCookieUser(session);
		if (!s.getIdUser().equals(id) && !s.getIdUser().equals("admin"))
			throw new NotAuthorizedException("Invalid user : " + s.getIdUser());
		return s;
	}

	/**
	 * Throws exception if not appropriate user for operation on Channel
	 */
	private Session checkCookieUser(Cookie session) throws NotAuthorizedException {
		if (session == null || session.getValue() == null)
			throw new NotAuthorizedException("No session initialized");
		Session s = getSession(session.getValue());
		if (s == null || s.getIdUser() == null || s.getIdUser().length() == 0)
			throw new NotAuthorizedException("No valid session initialized");
		return s;
	}

	public void close() {
		client.close();
	}
}
