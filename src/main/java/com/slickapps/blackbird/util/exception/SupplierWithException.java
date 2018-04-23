package com.slickapps.blackbird.util.exception;

@FunctionalInterface
public interface SupplierWithException<T> {

	T get() throws Exception;

}