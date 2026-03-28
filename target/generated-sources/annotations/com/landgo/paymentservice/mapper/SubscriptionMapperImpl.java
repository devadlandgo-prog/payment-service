package com.landgo.paymentservice.mapper;

import com.landgo.paymentservice.dto.response.SubscriptionResponse;
import com.landgo.paymentservice.entity.Subscription;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-28T19:29:21+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SubscriptionMapperImpl implements SubscriptionMapper {

    @Override
    public SubscriptionResponse toResponse(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }

        SubscriptionResponse.SubscriptionResponseBuilder subscriptionResponse = SubscriptionResponse.builder();

        subscriptionResponse.amount( subscription.getAmount() );
        subscriptionResponse.autoRenew( subscription.isAutoRenew() );
        subscriptionResponse.canAccessPremiumListings( subscription.isCanAccessPremiumListings() );
        subscriptionResponse.canContactVendorDirectly( subscription.isCanContactVendorDirectly() );
        subscriptionResponse.endDate( subscription.getEndDate() );
        subscriptionResponse.id( subscription.getId() );
        subscriptionResponse.maxSavedLands( subscription.getMaxSavedLands() );
        subscriptionResponse.maxVendorViewsPerMonth( subscription.getMaxVendorViewsPerMonth() );
        subscriptionResponse.paymentMethod( subscription.getPaymentMethod() );
        subscriptionResponse.plan( subscription.getPlan() );
        subscriptionResponse.startDate( subscription.getStartDate() );
        subscriptionResponse.status( subscription.getStatus() );

        subscriptionResponse.isActive( subscription.isActive() );

        return subscriptionResponse.build();
    }
}
