package com.slickapps.blackbird;

import java.util.List;

import com.slickapps.blackbird.listener.BlackbirdEventListener;

public interface EventListenerProvider {

	List<BlackbirdEventListener> getEventListeners();
	
}
