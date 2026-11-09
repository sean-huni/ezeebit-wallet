package io.ezeebit.gateway.service;

import java.time.Duration;

import io.ezeebit.gateway.domain.Admission;

public interface MerchantBuckets {

	Duration RETRY_AFTER = Duration.ofSeconds(1);

	Admission tryConsume(String key);

	boolean ping();

}
