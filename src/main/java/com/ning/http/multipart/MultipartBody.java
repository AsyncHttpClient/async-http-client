
package com.ning.http.multipart;

import com.ning.http.client.RandomAccessBody;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

public class MultipartBody implements RandomAccessBody {
	
	public MultipartBody(List<com.ning.http.client.Part> parts, String boundary, String contentLength) {
		_boundary = MultipartEncodingUtil.getAsciiBytes(boundary.substring("multipart/form-data; boundary=".length()));
		_contentLength = Long.parseLong(contentLength);
		_parts = parts;
		
		_files = new ArrayList<RandomAccessFile>();
		
		_startPart = 0;
	}

	public void close() throws IOException {
		for(RandomAccessFile file : _files) {
			file.close();
		}
	}

	public long getContentLength() {
		return _contentLength;
	}

	public long read(ByteBuffer buffer) throws IOException {
		// TODO Not implemented
		return 0;
	}

	public long transferTo(long position, long count, WritableByteChannel target)
			throws IOException {
		
		long overallLength = 0;
		
		if(_startPart == _parts.size()) {
			return overallLength;
		}
		
		long availableLength = count;
		
		int tempPart = _startPart;
		long totalLength = 0;
		boolean full = false;
		
		while(!full && tempPart < _parts.size()) {
			Part currentPart = (Part) _parts.get(tempPart);
			
			currentPart.setPartBoundary(_boundary);
		
			long length = currentPart.length();
			
			if((length + totalLength) < availableLength ) {
				totalLength += length;
				tempPart++;
				
				if(currentPart.getClass().equals(StringPart.class)) {
					
					ByteArrayOutputStream outputStream = 
						new ByteArrayOutputStream();
					
					Part.sendPart(outputStream, currentPart, _boundary);
					
					overallLength += writeToTarget(target, outputStream);
				}
				else if(currentPart.getClass().equals(FilePart.class)) {
					
					FilePart filePart = (FilePart)currentPart;
					
					ByteArrayOutputStream overhead = 
						new ByteArrayOutputStream();
					
					filePart.setPartBoundary(_boundary);
					
					filePart.sendStart(overhead);
			        filePart.sendDispositionHeader(overhead);
			        filePart.sendContentTypeHeader(overhead);
			        filePart.sendTransferEncodingHeader(overhead);
			        filePart.sendEndOfHeader(overhead);
					
					overallLength += writeToTarget(target, overhead);
						
					FilePartSource source = (FilePartSource)filePart.getSource();
					
					File file = source.getFile();
					
					RandomAccessFile raf = new RandomAccessFile(file, "r");
					_files.add(raf);
					
					FileChannel fc = raf.getChannel();

						
					long fileLength = fc.transferTo(0, file.length(), target);
						
					if(fileLength != file.length()) {
						System.out.println("Did not complete file.");
					}
					
					ByteArrayOutputStream endOverhead = 
						new ByteArrayOutputStream();
					
					filePart.sendEnd(endOverhead);
					
					overallLength += this.writeToTarget(target, endOverhead);
				}
			}
			else {
				full = true;
			}
		}
		
		ByteArrayOutputStream endWriter = 
			new ByteArrayOutputStream();
		
		Part.sendMessageEnd(endWriter, _boundary);
		
		overallLength += writeToTarget(target, endWriter);
		
		_startPart = tempPart;
		
		return overallLength;
	}

	private long writeToTarget(
		WritableByteChannel target,	ByteArrayOutputStream byteWriter)
			throws IOException {
		
		int written = 0;
		synchronized(byteWriter) {
			while((target.isOpen()) && (written < byteWriter.size())) {
				ByteBuffer message = ByteBuffer.wrap(byteWriter.toByteArray());
				written = target.write(message);
				if(written != byteWriter.size()) {
					System.out.println("Waiting...");
					try {
						byteWriter.wait(1000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
		return written;
	}

	private byte[] _boundary;
	private long _contentLength;
	private List<com.ning.http.client.Part> _parts;
	private List<RandomAccessFile> _files;
	private int _startPart;
	
}
