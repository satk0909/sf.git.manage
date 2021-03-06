package gk0909c.sf.git.manage.sfdc;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sforce.soap.metadata.AsyncResult;
import com.sforce.soap.metadata.DeployDetails;
import com.sforce.soap.metadata.DeployMessage;
import com.sforce.soap.metadata.DeployOptions;
import com.sforce.soap.metadata.DeployResult;
import com.sforce.soap.metadata.MetadataConnection;
import com.sforce.soap.metadata.RunTestFailure;
import com.sforce.soap.metadata.RunTestsResult;
import com.sforce.ws.ConnectionException;

import gk0909c.sf.git.manage.zip.ZipInfo;

/**
 * sfdc deploy.
 * @author satk0909
 *
 */
public class SfdcDeployer {
	private static final long ONE_SECOND = 1000;
	private static final int MAX_NUM_POLL_REQUESTS = 50;
	
	private MetadataConnection metaConn;
	private Logger logger = LoggerFactory.getLogger(SfdcDeployer.class);
	
	/**
	 * set up sfdc connection.
	 * @param info SfdcInfo
	 * @throws ConnectionException
	 */
	public SfdcDeployer(SfdcInfo info) throws ConnectionException {
		SfdcConnector conn = SfdcConnector.getConnection(info);
		metaConn = conn.getMetadataConnection();
	}
	
	/**
	 * deploy metadata
	 * @param zipInfo
	 * @throws Exception
	 */
	public void deployMetadata(ZipInfo zipInfo) throws Exception {
		// zipファイル読み込み
		FileInputStream fileInputStream = new FileInputStream(zipInfo.getZipPath());
		byte zipBytes[] = null;
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int bytesRead = 0;
			while (-1 != (bytesRead = fileInputStream.read(buffer))) {
				bos.write(buffer, 0, bytesRead);
			}
			zipBytes = bos.toByteArray();
		} finally {
			fileInputStream.close();
		}
		
		// デプロイ
		DeployOptions deployOptions = new DeployOptions();
		deployOptions.setPerformRetrieve(false);
		deployOptions.setRollbackOnError(true);
		AsyncResult asyncResult = metaConn.deploy(zipBytes, deployOptions);
		DeployResult result = waitForDeployCompletion(asyncResult.getId());
		
		// 結果確認
		if (!result.isSuccess()) {
			printErrors(result, "Final list of failures:\n");
			throw new Exception("The files were not successfully deployed");
		}
	}
	
	/**
	 * wait for deploy completion.
	 * @param asyncResultId
	 * @return
	 * @throws Exception
	 */
	private DeployResult waitForDeployCompletion(String asyncResultId) throws Exception {
		int poll = 0;
		long waitTimeMilliSecs = ONE_SECOND;
		DeployResult deployResult;
		boolean fetchDetails;
		do {
			Thread.sleep(waitTimeMilliSecs);
			// double the wait time for the next iteration
			waitTimeMilliSecs *= 2;
			if (poll++ > MAX_NUM_POLL_REQUESTS) {
				throw new Exception(
						"Request timed out. If this is a large set of metadata components, "
								+
						"ensure that MAX_NUM_POLL_REQUESTS is sufficient.");
			}
			// Fetch in-progress details once for every 3 polls
			fetchDetails = (poll % 3 == 0);
			deployResult = metaConn.checkDeployStatus(asyncResultId, fetchDetails);
			logger.info("Status is: " + deployResult.getStatus());
			
			if (!deployResult.isDone() && fetchDetails) {
				printErrors(deployResult, "Failures for deployment in progress:\n");
			}
		}
		while (!deployResult.isDone());
		if (!deployResult.isSuccess() && deployResult.getErrorStatusCode() != null) {
			throw new Exception(deployResult.getErrorStatusCode() + " msg: " +
					deployResult.getErrorMessage());
		}
		if (!fetchDetails) {
			// Get the final result with details if we didn't do it in the last attempt.
			deployResult = metaConn.checkDeployStatus(asyncResultId, true);
		}
		return deployResult;
	}
	
	/**
	 * print deployErrors
	 * @param result
	 * @param messageHeader
	 */
	private void printErrors(DeployResult result, String messageHeader) {
		DeployDetails details = result.getDetails();
		if (details != null) {
			DeployMessage[] componentFailures = details.getComponentFailures();
			for (DeployMessage failure : componentFailures) {
				String loc = "(" + failure.getLineNumber() + ", " + failure.getColumnNumber() + ")";
				
				if (loc.length() == 0 && !failure.getFileName().equals(failure.getFullName())) {
					loc = "(" + failure.getFullName() + ")";
				}
				
				logger.error(failure.getFileName() + loc + " : " + failure.getProblem());
			}
			
			RunTestsResult rtr = details.getRunTestResult();
			if (rtr.getFailures() != null) {
				for (RunTestFailure failure : rtr.getFailures()) {
					String n = (failure.getNamespace() == null ? "" :
						(failure.getNamespace() + ".")) + failure.getName();
					
					logger.error("Test failure, method: " + n + "." +
							failure.getMethodName() + " -- " + failure.getMessage() +
							" stack " + failure.getStackTrace() + "\n\n");
				}
			}
		}
	}
}
