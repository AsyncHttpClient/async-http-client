
package com.ning.http.multipart;

import com.ning.http.client.RandomAccessBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
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
		
		int tempPart = _startPart;
		
		for(com.ning.http.client.Part part : _parts) {
			if(part instanceof Part) {
				overallLength += handleMultiPart(target, (Part)part);
			}
			else {
				overallLength += handleClientPart(target, part);
			}
		
			tempPart++;
		}
		ByteArrayOutputStream endWriter = 
			new ByteArrayOutputStream();
		
		Part.sendMessageEnd(endWriter, _boundary);
		
		overallLength += writeToTarget(target, endWriter);
		
		_startPart = tempPart;
		
		return overallLength;
	}

	private long handleClientPart(
		WritableByteChannel target,	com.ning.http.client.Part part) throws IOException {
			
		if(part.getClass().equals(com.ning.http.client.StringPart.class)) {
			com.ning.http.client.StringPart stringPart = (com.ning.http.client.StringPart)part;
			
			StringPart currentPart = new StringPart(stringPart.getName(), stringPart.getValue());

			return handleStringPart(target,currentPart);
		}
		else if(part.getClass().equals(com.ning.http.client.FilePart.class)) {
			com.ning.http.client.FilePart currentPart = (com.ning.http.client.FilePart)part;
			
			FilePart filePart =  new FilePart(currentPart.getName(), currentPart.getFile());
			
			return handleFilePart(target, filePart);
		}
		else if(part.getClass().equals(com.ning.http.client.ByteArrayPart.class)) {
			com.ning.http.client.ByteArrayPart bytePart = (com.ning.http.client.ByteArrayPart)part;
			
		    ByteArrayPartSource source = new ByteArrayPartSource(bytePart.getFileName(), bytePart.getData());
		    
			FilePart filePart = new FilePart(bytePart.getName(), source, bytePart.getMimeType(), bytePart.getCharSet());
			
			return handleByteArrayPart(target, filePart, bytePart.getData());
		}
		
		return 0;
	}

	private long handleByteArrayPart(WritableByteChannel target,
			FilePart filePart, byte[] data) throws IOException {
		
		int length = 0;
		
		//length += handleFileHeaders(target, filePart);
		
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		
		Part.sendPart(output, filePart, _boundary);
		
		length += writeToTarget(target, output);

		//length += handleFileEnd(target, filePart);
		
		return length;

	}

	private long handleFileEnd(WritableByteChannel target, FilePart filePart) 
		throws IOException {
		
		ByteArrayOutputStream endOverhead =
			new ByteArrayOutputStream();
			
		filePart.sendEnd(endOverhead);
			
		return this.writeToTarget(target, endOverhead);
	}

	private long handleFileHeaders(WritableByteChannel target,
			FilePart filePart) throws IOException {
		filePart.setPartBoundary(_boundary);
			
		ByteArrayOutputStream overhead = new ByteArrayOutputStream();

		filePart.setPartBoundary(_boundary);
			
		filePart.sendStart(overhead);
		filePart.sendDispositionHeader(overhead);
		filePart.sendContentTypeHeader(overhead);
		filePart.sendTransferEncodingHeader(overhead);
		filePart.sendEndOfHeader(overhead);
			
		return writeToTarget(target, overhead);
	}

	private long handleFilePart(WritableByteChannel target, FilePart filePart) 
		throws IOException, FileNotFoundException {
		
		int length = 0;
		
		length += handleFileHeaders(target, filePart);
				
		FilePartSource source = (FilePartSource)filePart.getSource();
			
		File file = source.getFile();
			
		RandomAccessFile raf = new RandomAccessFile(file, "r");
			_files.add(raf);
			
		FileChannel fc = raf.getChannel();

				
		long fileLength = fc.transferTo(0, file.length(), target);
				
		if(fileLength != file.length()) {
			System.out.println("Did not complete file.");
		}
			
		length += handleFileEnd(target, filePart);
		
		return length;
	}

	private long handleStringPart(WritableByteChannel target, StringPart currentPart) 
		throws IOException {
		
		currentPart.setPartBoundary(_boundary);
		
		ByteArrayOutputStream outputStream = 
			new ByteArrayOutputStream();
		
		Part.sendPart(outputStream, currentPart, _boundary);
		
		return writeToTarget(target, outputStream);
	}

	private long handleMultiPart(WritableByteChannel target, Part currentPart) 
		throws IOException, FileNotFoundException {
		
		currentPart.setPartBoundary(_boundary);
			
		if(currentPart.getClass().equals(StringPart.class)) {
			return handleStringPart(target, (StringPart)currentPart);
		}
		else if(currentPart.getClass().equals(FilePart.class)) {
			FilePart filePart = (FilePart)currentPart;
				
			return handleFilePart(target, filePart);
		}
		return 0;
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
