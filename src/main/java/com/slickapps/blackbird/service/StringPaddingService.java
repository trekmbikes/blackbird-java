package com.slickapps.blackbird.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StringPaddingService {

	Map<String, Integer> maxLengths = new ConcurrentHashMap<>();

	public int getMaxLen(String key, String input) {
		int curLength = maxLengths.computeIfAbsent(key, p -> 0);
		if (input.length() > curLength) {
			curLength = input.length();
			maxLengths.put(key, curLength);
		}
		return curLength;
	}

}
