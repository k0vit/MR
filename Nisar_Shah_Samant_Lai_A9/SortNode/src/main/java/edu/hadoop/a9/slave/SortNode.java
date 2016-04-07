package edu.hadoop.a9.slave;

import static spark.Spark.post;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import edu.hadoop.a9.common.ClientNodeCommWrapper;
import edu.hadoop.a9.common.S3Wrapper;

public class SortNode {
	static String accessKey;
	static String secretKey;
	static String clientIp;
	public static final int DRY_BULB_COL = 8;
	static int TOTAL_NO_OF_SORT_NODES;
	static Map<String, Long> ipToMaxMap = new HashMap<String, Long>();
	static Map<String, Long> ipToMinMap = new HashMap<String, Long>();
	static long MINIMUM_PARTITION;
	static long MAXIMUM_PARTITION;
	static String INSTANCE_IP;
	static long INSTANCE_ID;
	static ArrayList<String[]> unsortedData = new ArrayList<String[]>();
	public static final String PORT_FOR_SORT_NODE_COMM = "1234";
	public static final String PORT_FOR_CLIENT_COMM = "4567";
	public static final int NUMBER_OF_REQUESTS_STORED = 20000;
	public static final String PARTITION_URL = "partitions";
	public static final String END_URL = "end";
	public static final String END_OF_SORTING_URL = "signals";
	static int NO_OF_SORT_NODES_WHERE_DATA_IS_RECEIVED = 0;
	
	public static void main(String[] args) {
		if (args.length != 5) {
			System.err.println("Usage: SortNode <input s3 path> <output s3 path> <config file path s3> <aws access key> <aws secret key>");
			for (int i = 0; i < args.length; i++) {
				System.err.println(args[i]);
			}
			System.err.println(args.length);
			System.exit(-1);
		}
		
		String outputS3Path = args[1];
		String configFilePath = args[2];
		accessKey = args[3];
		secretKey = args[4];
		
		log.info("Application Initialized");
		
		try {
			INSTANCE_IP = InetAddress.getLocalHost().getHostName();
			BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
			AmazonS3Client s3client = new AmazonS3Client(awsCredentials);
			S3Wrapper wrapper = new S3Wrapper(s3client);
			
			//This downloads the config file on the local directory.
			String configFileName = wrapper.readOutputFromS3(configFilePath, awsCredentials);
			readFileAndSetProps(configFileName);
			
			//This is the first thing node will do as soon as it is up.
			sendSampleDistribution(wrapper, awsCredentials);
			
			readPartitionsFromClient();
			
			receiveDataFromOtherSortNodes();
			
			checkIfAllDataReceived(outputS3Path, wrapper);
			
		} catch (IOException | InterruptedException e) {
			log.severe(e.getMessage());
		}
		
	}
	
	private static void receiveDataFromOtherSortNodes() {

		post("/records", (request, response) -> {
			String recordList = request.body();
			String[] records = recordList.split(":");
			for (String record : records) {
				unsortedData.add(record.split(","));
			}
			response.status(200);
			response.body("Awesome");
			return response.body().toString();
		});		
		
	}

	/**
	 * If All data is received then start sorting the data you have and write it to S3.
	 */
	private static void checkIfAllDataReceived(String outputS3Path, S3Wrapper wrapper) {
		post("/end", (request, response) -> {
			NO_OF_SORT_NODES_WHERE_DATA_IS_RECEIVED++;
			if (NO_OF_SORT_NODES_WHERE_DATA_IS_RECEIVED == TOTAL_NO_OF_SORT_NODES) {
				sortYourOwnData();
				uploadDataToS3(outputS3Path, wrapper);
				ClientNodeCommWrapper.SendData(clientIp, PORT_FOR_CLIENT_COMM, END_OF_SORTING_URL, "SORTED");
			}
			return response.body().toString();
		});
		
	}

	private static void uploadDataToS3(String outputS3Path, S3Wrapper wrapper) {
		ArrayList<String[]> nowSortedData = unsortedData;
		String fileName = "part-r-" + INSTANCE_ID + ".csv";
		try {
			CSVWriter writer = new CSVWriter(new FileWriter(fileName));
			writer.writeAll(nowSortedData);
			writer.close();
			wrapper.uploadFile(fileName, outputS3Path);
		} catch (IOException e) {
			log.severe(e.getMessage());
		}
	}

	public static void readPartitionsFromClient() {
		JSONParser parser = new JSONParser();
		Map<String, Integer> ipToCountOfRequests = new HashMap<String, Integer>();
		Map<String, StringBuilder> ipToActualRequestString = new HashMap<String, StringBuilder>();
		
		post("/partitions", (request, response) -> {
			// String partitions;
			response.status(200);
			response.body("SUCCESS");
			JSONObject entireJSON = (JSONObject) parser.parse(request.body().toString());
			JSONArray array = (JSONArray) entireJSON.get("partitions");
			for (int i = 0; i < array.size(); i++) {
				JSONObject jsonObject = (JSONObject) array.get(i);
				Long minimumPartition = (Long) jsonObject.get("min");
				Long maximumPartition = (Long) jsonObject.get("max");
				String nodeIp = (String) jsonObject.get("nodeIp");
				Long instanceId = (Long) jsonObject.get("instanceId");
				if (nodeIp == INSTANCE_IP) {
					MAXIMUM_PARTITION = maximumPartition;
					MINIMUM_PARTITION = minimumPartition;
					INSTANCE_ID = instanceId;
				}
				ipToMaxMap.put(nodeIp, maximumPartition);
				ipToMinMap.put(nodeIp, minimumPartition);
			}
			
			// Read local data line by line
			File[] dataFolder = listDirectory(System.getProperty("user.dir"));
			for (File file : dataFolder) {
				if (!checkFileExtensionsIsGz(file.getName())) continue;
				FileInputStream fis = new FileInputStream(file);
				InputStream gzipStream = new GZIPInputStream(fis);
				BufferedReader br = new BufferedReader(new InputStreamReader(gzipStream));
				CSVReader reader = new CSVReader(br);
				String[] line = null;
				reader.readNext();
				while ((line = reader.readNext()) != null) {
					if (!(line.length < 9) && !line[DRY_BULB_COL].equals("-")) {
						try {
							double dryBulbTemp = Double.parseDouble(line[DRY_BULB_COL]);
							// Check which partition it lies within and send to the sortNode required
							for (String instanceIp : ipToMaxMap.keySet()) {
								if (dryBulbTemp >= ipToMinMap.get(instanceIp) && dryBulbTemp <= ipToMaxMap.get(instanceIp)) {
									if (instanceIp == INSTANCE_IP) {
										unsortedData.add(line);
									} else {
										if (ipToCountOfRequests.get(instanceIp) < NUMBER_OF_REQUESTS_STORED) {
											ipToCountOfRequests.put(instanceIp, ipToCountOfRequests.get(instanceIp) + 1);
											ipToActualRequestString.put(instanceIp, 
													ipToActualRequestString.get(instanceIp).append(":" + line));
										} else {
											sendRequestToSortNode(instanceIp, ipToCountOfRequests, ipToActualRequestString);
										}
									}
									break;
								}
							}
						} catch (Exception e) {
							log.severe("Error: " + e.getMessage());
						}
					} 
				}
				reader.close();
				br.close();
				
				for(String ipAddress : ipToCountOfRequests.keySet()) {
					sendRequestToSortNode(ipAddress, ipToCountOfRequests, ipToActualRequestString);
					// SEND EOF to signal end of file
					ClientNodeCommWrapper.SendData(ipAddress, PORT_FOR_SORT_NODE_COMM, END_URL, "EOF");
				}
				
			}
			
			
			return response.body().toString();
		});
		
	}

	private static void sendRequestToSortNode(String instanceIp, Map<String, Integer> ipToCountOfRequests, Map<String, StringBuilder> ipToActualRequestString) {
		StringBuilder sb = ipToActualRequestString.get(instanceIp);
		ipToActualRequestString.put(instanceIp, new StringBuilder());
		ipToCountOfRequests.put(instanceIp, 0);
		String recordList = sb.toString();
		try {
			ClientNodeCommWrapper.SendData(instanceIp, PORT_FOR_SORT_NODE_COMM, PARTITION_URL ,recordList);
		} catch (UnirestException e) {
			log.severe(e.getMessage());
		}
	}

	private static boolean checkFileExtensionsIsGz(String fileName) {
		String format = fileName.substring(fileName.lastIndexOf(".") + 1);
		if (format.equals("gz")) {
			return true;
		} else {
			return false;
		}
	}

	private static void readFileAndSetProps(String configFileName) throws NumberFormatException, IOException {
		FileReader fr = new FileReader(configFileName);
		BufferedReader br = new BufferedReader(fr);
		String line = null;
		while ((line = br.readLine()) != null) {
			String[] column = line.split(",");
			if (column[3].equals("S")) {
				TOTAL_NO_OF_SORT_NODES++;
			}
		}
		br.close();
	}
	
	public static void sendSampleDistribution(S3Wrapper wrapper, BasicAWSCredentials awsCredentials) {
		try {
			post("/files", (request, response) -> {
				//Receive request from client for the files which need to be taken care of
				clientIp = request.ip();
				response.status(200);
				response.body("SUCCESS");
				String fileString = request.body();
				String[] filenames = downloadAndStoreFileInLocal(wrapper, fileString, awsCredentials);
				randomlySample(filenames);
				return response.body();
			});

		} catch (Exception exp) {
			StringWriter sw = new StringWriter();
			exp.printStackTrace(new PrintWriter(sw));
			log.severe(String.format("Error sending sampled distribution : %s", exp.getMessage()));
			log.severe(sw.toString());
		}
	}
	
	/**
	 * This method takes the string of filenames, creates separate threads and sends each file sampling to client Node. 
	 * @param filenames
	 */
	private static void randomlySample(String[] filenames) {
		ThreadPoolExecutor executor = (ThreadPoolExecutor)Executors.newCachedThreadPool();
		for (String filename : filenames) {
			Task task = new Task(filename, clientIp);
			executor.execute(task);
		}
		executor.shutdown();
	}

	private static String[] downloadAndStoreFileInLocal(S3Wrapper wrapper, String fileString, BasicAWSCredentials awsCredentials) {
		String[] files = fileString.split(",");
		for (String file : files) {
			try {
				wrapper.readOutputFromS3(file, awsCredentials);
			} catch (IOException | InterruptedException e) {
				log.severe(e.getMessage());
			}
		}
		return files;
	}
	
	private static void sortYourOwnData() {
		Collections.sort(unsortedData, new Comparator<String[]>() {
			@Override
			public int compare(String[] o1, String[] o2) {
				return (o1[8].compareTo(o2[8]));
			}
		});
	}

	public static File[] listDirectory(String directoryPath) {
		File directory = new File(directoryPath);
		File[] files = directory.listFiles();
		return files;
	}

	private static final Logger log = Logger.getLogger(SortNode.class.getName());
}
