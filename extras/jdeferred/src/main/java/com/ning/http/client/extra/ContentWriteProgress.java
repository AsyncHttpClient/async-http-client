package com.ning.http.client.extra;

public class ContentWriteProgress implements HttpProgress {
	private final long amount;
	private final long current;
	private final long total;

	public ContentWriteProgress(long amount, long current, long total) {
		this.amount = amount;
		this.current = current;
		this.total = total;
	}

	public long getAmount() {
		return amount;
	}

	public long getCurrent() {
		return current;
	}

	public long getTotal() {
		return total;
	}

	@Override
	public String toString() {
		return "ContentWriteProgress [amount=" + amount + ", current="
				+ current + ", total=" + total + "]";
	}
	
}
