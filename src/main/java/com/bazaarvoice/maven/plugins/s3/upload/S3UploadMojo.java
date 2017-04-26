package com.bazaarvoice.maven.plugins.s3.upload;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.ObjectMetadataProvider;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

@Mojo(name = "s3-upload")
public class S3UploadMojo extends AbstractMojo {
	
	/** Access key for S3. */
	@Parameter(property = "s3-upload.accessKey")
	private String accessKey;

	/** Secret key for S3. */
	@Parameter(property = "s3-upload.secretKey")
	private String secretKey;

	/** Force override of region for S3 regions such as EU. */
	@Parameter(property = "s3-upload.region", defaultValue = "ap-northeast-2")
	private String region;

	/**
	 * Execute all steps up except the upload to the S3. This can be set to true
	 * to perform a "dryRun" execution.
	 */
	@Parameter(property = "s3-upload.doNotUpload", defaultValue = "false")
	private boolean doNotUpload;

	/** The file/folder to upload. */
	@Parameter(property = "s3-upload.source", required = true)
	private File source;

	/** The bucket to upload into. */
	@Parameter(property = "s3-upload.bucketName", required = true)
	private String bucketName;

	/** The file/folder (in the bucket) to create. */
	@Parameter(property = "s3-upload.destination")
	private String destination;

	/** In the case of a directory upload, recursively upload the contents. */
	@Parameter(property = "s3-upload.recursive", defaultValue = "false")
	private boolean recursive;

	/** make public accessable */
	@Parameter(property = "s3-upload.makePublic", defaultValue = "true")
	private boolean makePublic;

	@Override
	public void execute() throws MojoExecutionException {
		if (!source.exists()) {
			throw new MojoExecutionException("File/folder doesn't exist: " + source);
		}

		AmazonS3 s3 = getS3Client(accessKey, secretKey);

		if (!s3.doesBucketExist(bucketName)) {
			throw new MojoExecutionException("Bucket doesn't exist: " + bucketName);
		}

		if (doNotUpload) {
			getLog().info(String.format("File %s would have be uploaded to s3://%s/%s (dry run)", source, bucketName, destination));
			return;
		}

		boolean success = upload(s3, source);
		
		if (!success) {
			throw new MojoExecutionException("Unable to upload file to S3.");
		}

		if (destination == null || "".equals(destination)) {
			getLog().info(String.format("File %s uploaded to s3://%s", source, bucketName));
		} else {
			getLog().info(String.format("File %s uploaded to s3://%s/%s", source, bucketName, destination));
		}
	}

	private AmazonS3 getS3Client(String accessKey, String secretKey) {
		AWSCredentialsProvider provider;
		
		if (accessKey != null && secretKey != null) {
			AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
			provider = new AWSStaticCredentialsProvider(credentials);
		} else {
			provider = new DefaultAWSCredentialsProviderChain();
		}

		return AmazonS3ClientBuilder.standard().withRegion(Regions.fromName(region)).withCredentials(provider).build();
	}

	private boolean upload(AmazonS3 s3, File sourceFile) throws MojoExecutionException {
		TransferManager mgr = TransferManagerBuilder.standard().withS3Client(s3).build();

		Transfer transfer = null;
		if (sourceFile.isFile()) {
			String key = null;
			
			if (destination != null && !"".equals(destination)) {
				key = destination + "/" + sourceFile.getName();
			} else {
				key = sourceFile.getName();
			}
			
			PutObjectRequest request = new PutObjectRequest(bucketName, key, sourceFile)
					.withCannedAcl(CannedAccessControlList.BucketOwnerFullControl);
			
			if (makePublic) {
				request.withCannedAcl(CannedAccessControlList.PublicRead);
			}
			
			transfer = mgr.upload(request);
		} else if (sourceFile.isDirectory()) {
			transfer = mgr.uploadDirectory(bucketName, destination, sourceFile, recursive,
					new ObjectMetadataProvider() {
						@Override
						public void provideObjectMetadata(final File file, final ObjectMetadata objectMetadata) {
							if (makePublic) {
								objectMetadata.setHeader(Headers.S3_CANNED_ACL, CannedAccessControlList.PublicRead);
							}
						}
					});
		} else {
			throw new MojoExecutionException("File is neither a regular file nor a directory " + sourceFile);
		}
		
		try {
			getLog().debug("Transferring " + transfer.getProgress().getTotalBytesToTransfer() + " bytes...");
			transfer.waitForCompletion();
			getLog().info("Transferred " + transfer.getProgress().getBytesTransferred() + " bytes.");
		} catch (InterruptedException e) {
			return false;
		}

		return transfer.getState() == Transfer.TransferState.Completed;
	}
}
