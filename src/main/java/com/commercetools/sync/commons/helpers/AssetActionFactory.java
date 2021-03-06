package com.commercetools.sync.commons.helpers;

import com.commercetools.sync.commons.BaseSyncOptions;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.models.Asset;
import io.sphere.sdk.models.AssetDraft;

import javax.annotation.Nonnull;
import java.util.List;


/**
 * Helper class for building update actions for assets that are contained in the resource of type {@code T}.
 *
 * @param <T> the type of the resource the asset update actions are built for.
 */
public abstract class AssetActionFactory<T> {
    public BaseSyncOptions syncOptions = null;

    /**
     * Takes a matching old asset and a new asset and computes the update actions needed to sync them.
     *
     * @param oldAsset      the old asset to compare.
     * @param newAssetDraft the matching new asset draft.
     * @return update actions needed to sync the two assets.
     */
    public abstract List<UpdateAction<T>> buildAssetActions(@Nonnull final Asset oldAsset,
                                                            @Nonnull final AssetDraft newAssetDraft);

    /**
     * Takes an asset key to build a RemoveAsset action of the type T.
     *
     * @param assetKey the key of the asset used un building the update action.
     * @return the built remove asset update action.
     */
    public abstract UpdateAction<T> buildRemoveAssetAction(@Nonnull final String assetKey);

    /**
     * Takes a list of asset ids to build a ChangeAssetOrder action of the type T.
     *
     * @param newAssetOrder the new asset order needed to build the action.
     * @return the built update action.
     */
    public abstract UpdateAction<T> buildChangeAssetOrderAction(@Nonnull final List<String> newAssetOrder);

    /**
     * Takes an asset draft and an asset position to build an AddAsset action of the type T.
     *
     * @param newAssetDraft the new asset draft to create an Add asset action for.
     * @param position      the position to add the new asset to.
     * @return the built update action.
     */
    public abstract UpdateAction<T> buildAddAssetAction(@Nonnull final AssetDraft newAssetDraft,
                                                        @Nonnull final Integer position);
}
