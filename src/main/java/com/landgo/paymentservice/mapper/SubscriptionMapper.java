package com.landgo.paymentservice.mapper;

import com.landgo.paymentservice.dto.response.SubscriptionResponse;
import com.landgo.paymentservice.entity.Subscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SubscriptionMapper {
    @Mapping(target = "isActive", expression = "java(subscription.isActive())")
    SubscriptionResponse toResponse(Subscription subscription);
}
