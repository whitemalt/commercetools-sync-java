package com.commercetools.sync.commons.utils;

import io.sphere.sdk.models.Referenceable;
import io.sphere.sdk.models.ResourceIdentifier;

import javax.annotation.Nullable;

import static java.util.Optional.ofNullable;

public final class ResourceIdentifierUtils {

    /**
     * Given a {@link Referenceable} {@code resource} of the type {@code T}, if it is not null, this method applies the
     * {@link Referenceable#toResourceIdentifier()} method to return it as a {@link ResourceIdentifier} of
     * the type {@code T}. If it is {@code null}, this method returns {@code null}.
     *
     * @param resource represents the resource to return as a {@link ResourceIdentifier} if not {@code null}.
     * @param <T>      type of the resource supplied.
     * @param <S>      represents the type of the {@link ResourceIdentifier} returned.
     * @return the supplied resource in the as a {@link ResourceIdentifier} if not {@code null}. If it is {@code null},
     *         this method returns {@code null}.
     */
    @Nullable
    public static <T extends Referenceable<S>, S> ResourceIdentifier<S> toResourceIdentifierIfNotNull(
        @Nullable final T resource) {
        return ofNullable(resource)
            .map(Referenceable::toResourceIdentifier)
            .orElse(null);
    }

    private ResourceIdentifierUtils() {
    }
}
