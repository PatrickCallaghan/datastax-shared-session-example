package com.datastax.sharedsession;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.demo.utils.PropertyHelper;
import com.datastax.demo.utils.Timer;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;

public class Main {

	private static Logger logger = LoggerFactory.getLogger(Main.class);
	
	private Session session;
	private static String keyspaceName = "datastax_shared_session_demo";
	private static String tableName = keyspaceName + ".timeline";

	static final String INSERT_INTO_FOLLOWERS = "Insert into " + tableName
			+ " (userid, time, follower_id, message) values (?,?,?,?);";

	private PreparedStatement insertStmt;

	public Main() {

		String contactPointsStr = PropertyHelper.getProperty("contactPoints", "localhost");
		String noOfThreadsStr = PropertyHelper.getProperty("noOfThreads", "1");
		int messageCount = Integer.parseInt(PropertyHelper.getProperty("messageCount", "10"));
		int noOfFollowers  = Integer.parseInt(PropertyHelper.getProperty("noOfFollowers", "1000"));
				
		int noOfThreads = Integer.parseInt(noOfThreadsStr);
		
		//Create shared queue 
		ConcurrentLinkedQueue<SharedMessage> queue = new ConcurrentLinkedQueue<SharedMessage>();
		ExecutorService executor = Executors.newFixedThreadPool(noOfThreads);

		//Set up cluster and session
		Cluster cluster = Cluster.builder().addContactPoints(contactPointsStr.split(",")).build();
		this.session = cluster.connect();

		insertStmt = session.prepare(INSERT_INTO_FOLLOWERS);

		System.out.println("Cluster and Session created.");

		for (int i = 0; i < noOfThreads; i++) {
			executor.execute(new FollowerWriter(session, queue));
		}
		
		UUID userId = UUID.randomUUID();						
		List<SharedMessage> followers = createRandomFollowers(userId, noOfFollowers);
		Timer timer = new Timer();
		timer.start();		
		
		for (int i=0; i < messageCount; i++){
			
			//Simulate a message going to thousands of followers. The message needs to 
			//be inserted in each of the follower timelines.						
			for (SharedMessage sharedMessage : followers){
				queue.offer(sharedMessage);
			}
		}
		
		while(!queue.isEmpty()){
			logger.info("Messages left to send " + queue.size());
			
			sleep(1);
		}
		
		System.out.println("Shared Session test finished.");
		timer.end();
		
		logger.info("Completed " + messageCount + " messages to " +noOfFollowers + " followers in " + timer.getTimeTakenSeconds() + "secs" 
				+ " with " + noOfThreads + " threads.");

		sleep(1);
		
		cluster.close();
		System.exit(0);
	}

	private List<SharedMessage> createRandomFollowers(UUID userId, int idSize) {

		List<SharedMessage> followers = new ArrayList<SharedMessage>();

		for (int i=0; i < idSize; i ++){			
			followers.add(new SharedMessage(userId, UUID.randomUUID(), "Test Message " + i));
		}
		return followers;
	}

	class FollowerWriter implements Runnable {

		private Session session;
		private ConcurrentLinkedQueue<SharedMessage> queue;

		public FollowerWriter(Session session, ConcurrentLinkedQueue<SharedMessage> queue) {
			this.session = session;
			this.queue = queue;
		}

		@Override
		public void run() {			
			while(true){				
				SharedMessage message = queue.poll();
				
				if (message!=null){
					this.insertMessage(message);
				}				
			}				
		}

		private void insertMessage(SharedMessage message) {
			BoundStatement boundStmt = new BoundStatement(insertStmt);
			boundStmt.bind(message.getFollowerId(), UUIDs.timeBased(), message.getUserid(), message.getMessage() + " " + new Date().toString());
			session.execute(boundStmt);
		}
	}
	
	private void sleep(int seconds) {
		try {
			Thread.sleep(seconds * 1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		new Main();
	}
}
