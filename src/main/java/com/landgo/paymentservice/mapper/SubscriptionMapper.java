package com.landgo.paymentservice.mapper;

import com.landgo.paymentservice.dto.response.SubscriptionResponse;
import com.landgo.paymentservice.entity.Subscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SubscriptionMapper {
    @Mapping(target = "isActive", expression = "java(subscription.isActive())")
    @Mapping(target = "currency", constant = "CAD")
    @Mapping(source = "planCategory", target = "type")
    @Mapping(source = "planCategory", target = "productType")
    SubscriptionResponse toResponse(Subscription subscription);
}

