package com.commercetools.sync.inventories;

import com.commercetools.sync.commons.BaseSync;
import com.commercetools.sync.inventories.helpers.InventorySyncStatistics;
import com.commercetools.sync.inventories.utils.InventorySyncUtils;
import com.commercetools.sync.services.TypeService;
import com.commercetools.sync.services.impl.TypeServiceImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.sphere.sdk.channels.Channel;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.inventory.InventoryEntry;
import io.sphere.sdk.inventory.InventoryEntryDraft;
import io.sphere.sdk.inventory.InventoryEntryDraftBuilder;
import io.sphere.sdk.models.Reference;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * Default implementation of inventories sync process.
 */
public final class InventorySync extends BaseSync<InventoryEntryDraft, InventorySyncStatistics, InventorySyncOptions> {
    private static final String CTP_INVENTORY_FETCH_FAILED = "Failed to fetch existing inventory entries of SKUs %s.";
    private static final String CTP_CHANNEL_FETCH_FAILED = "Failed to fetch supply channels.";
    private static final String CTP_INVENTORY_ENTRY_UPDATE_FAILED = "Failed to update inventory entry of sku '%s' and "
        + "supply channel key '%s'.";
    private static final String INVENTORY_DRAFT_HAS_NO_SKU = "Failed to process inventory entry without sku.";
    private static final String INVENTORY_DRAFT_IS_NULL = "Failed to process null inventory draft.";
    private static final String CTP_CHANNEL_CREATE_FAILED = "Failed to create new supply channel of key '%s'.";
    private static final String CTP_INVENTORY_ENTRY_CREATE_FAILED = "Failed to create inventory entry of sku '%s' "
        + "and supply channel key '%s'.";
    private static final String CHANNEL_KEY_MAPPING_DOESNT_EXIST = "Failed to find supply channel of key '%s'.";

    private final InventoryService inventoryService;

    private final TypeService typeService;

    public InventorySync(@Nonnull final InventorySyncOptions syncOptions) {
        this(syncOptions, new InventoryServiceImpl(syncOptions.getCtpClient()),
                new TypeServiceImpl(syncOptions.getCtpClient()));
    }

    InventorySync(@Nonnull final InventorySyncOptions syncOptions, @Nonnull final InventoryService inventoryService,
                  @Nonnull final TypeService typeService) {
        super(new InventorySyncStatistics(), syncOptions);
        this.inventoryService = inventoryService;
        this.typeService = typeService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     *     <strong>NOTE:</strong> {@code inventoryDrafts} are compared with existing inventory entries by {@code sku}
     *     and {@code supplyChannel} key. Every {@link InventoryEntryDraft} that contains {@code supplyChannel} should
     *     either:
     *     <ul>
     *         <li>have {@code supplyChannel} expanded, that means
     *         {@code inventoryEntryDraft.getSupplyChannel().getObj()} should return {@link Channel} object,
     *         which contains channel key</li>
     *         <li>or have {@code supplyChannel} not expanded and {@code supplyChannel} key should be provided in
     *         place of reference id, that means {@code inventoryEntryDraft.getSupplyChannel().getObj()} should
     *         return {@code null} and {@code inventoryEntryDraft.getSupplyChannel().getId()} should
     *         return {@code supplyChannel} key</li>
     *     </ul>
     *     This is important for proper resources comparision.
     * </p>
     */
    @Override
    public CompletionStage<InventorySyncStatistics> sync(@Nonnull final List<InventoryEntryDraft>
                                                                       inventoryDrafts) {
        return super.sync(inventoryDrafts);
    }

    /**
     * Performs full process of synchronisation between inventory entries present in a system
     * and passed {@code inventories}. This is accomplished by:
     * <ul>
     *     <li>Comparing entries and drafts by {@code sku} and {@code supplyChannel} key</li>
     *     <li>Calculating of necessary updates and creation commands</li>
     *     <li>Actually <strong>performing</strong> changes in a target CTP project</li>
     * </ul>
     * The process is customized according to {@link InventorySyncOptions} passed to constructor of this object.
     *
     * <p><strong>Inherited doc:</strong>
     * {@inheritDoc}
     *
     * @param inventories {@link List} of {@link InventoryEntryDraft} resources that would be synced into CTP project.
     * @return {@link CompletionStage} with {@link InventorySyncStatistics} holding statistics of all sync
     *                                           processes performed by this sync instance
     */
    @Override
    protected CompletionStage<InventorySyncStatistics> process(@Nonnull final List<InventoryEntryDraft>
                                                                             inventories) {
        return getExpectedSupplyChannels(inventories)
            .thenCompose(channelsMap -> splitToBatchesAndProcess(inventories, channelsMap))
            .exceptionally(exception -> statistics);
    }

    /**
     * Iterates through the whole {@code inventories} list and accumulates its valid drafts to batches. Every batch
     * is then processed by {@link InventorySync#processBatch(List, ChannelsMap)}. For invalid drafts from
     * {@code inventories} "processed" and "failed" counters from statistics are incremented and error callback is
     * executed. Valid draft is a {@link InventoryEntryDraft} object that is not {@code null} and its SKU is not empty.
     *
     * @param inventories {@link List} of {@link InventoryEntryDraft} resources that would be synced into CTP project.
     * @param channelsMap cache of existing {@code supplyChannels}
     * @return {@link CompletionStage} with {@link InventorySyncStatistics} holding statistics of all sync
     *                                           processes performed by this sync instance
     */
    @Nonnull
    private CompletionStage<InventorySyncStatistics> splitToBatchesAndProcess(@Nonnull final List<InventoryEntryDraft>
                                                                                      inventories,
                                                                              @Nonnull final ChannelsMap channelsMap) {
        List<InventoryEntryDraft> accumulator = new ArrayList<>(syncOptions.getBatchSize());
        final List<CompletableFuture<Void>> completableFutures = new ArrayList<>();
        for (InventoryEntryDraft entry : inventories) {
            if (entry != null) {
                if (isNotEmpty(entry.getSku())) {
                    accumulator.add(entry);
                    if (accumulator.size() == syncOptions.getBatchSize()) {
                        final CompletableFuture<Void> batchProcessingFuture =
                            processBatch(accumulator, channelsMap)
                                .exceptionally(exception -> null)
                                .toCompletableFuture();
                        completableFutures.add(batchProcessingFuture);
                        accumulator = new ArrayList<>(syncOptions.getBatchSize());
                    }
                } else {
                    statistics.incrementProcessed();
                    statistics.incrementFailed();
                    syncOptions.applyErrorCallback(INVENTORY_DRAFT_HAS_NO_SKU, null);
                }
            } else {
                statistics.incrementProcessed();
                statistics.incrementFailed();
                syncOptions.applyErrorCallback(INVENTORY_DRAFT_IS_NULL, null);
            }
        }
        if (!accumulator.isEmpty()) {
            final CompletableFuture<Void> batchProcessingFuture =
                processBatch(accumulator, channelsMap)
                    .exceptionally(exception -> null)
                    .toCompletableFuture();
            completableFutures.add(batchProcessingFuture);
        }
        return CompletableFuture
            .allOf(completableFutures.toArray(new CompletableFuture[completableFutures.size()]))
            .thenApply(v -> statistics);
    }

    @Override
    @Nonnull
    public InventorySyncStatistics getStatistics() {
        return statistics;
    }

    /**
     * Methods tries to
     * <ul>
     *     <li>Fetch existing supply channels from CTP project</li>
     *     <li>Apply error callback if fetching supply channels fails</li>
     *     <li>Instantiate {@code supplyChannelKeyToId} map</li>
     *     <li>Create missing supply channels if needed</li>
     * </ul>
     * Method returns {@link CompletionStage} of {@link ChannelsMap} that contains cache of existing
     * {@code supplyChannels}. It may contain {@link SyncProblemException} when fetching supply channels results in
     * exception.
     *
     * @param drafts {@link List} containing {@link InventoryEntryDraft} objects where missing supply channels can occur
     * @return {@link CompletionStage} of {@link ChannelsMap} that contain mapping of supply channels'
     *      keys to ids, or {@link SyncProblemException} when fetching supply channels results in exception
     */
    private CompletionStage<ChannelsMap> getExpectedSupplyChannels(@Nonnull final List<InventoryEntryDraft>
                                                                               drafts) {
        return inventoryService.fetchAllSupplyChannels()
                .exceptionally(exception -> {
                    syncOptions.applyErrorCallback(CTP_CHANNEL_FETCH_FAILED, exception);
                    throw new SyncProblemException();
                })
                .thenApply(existingSupplyChannels -> ChannelsMap.Builder.of(existingSupplyChannels))
                .thenCompose(channelMappingBuilder -> createMissingSupplyChannels(drafts, channelMappingBuilder));
    }

    /**
     * When {@code ensureChannel} from {@link InventorySyncOptions} is set to {@code true} then attempts to create
     * missing supply channels. Missing supply channel is a supply channel of key that can not be found in CTP project,
     * but occurs in {@code drafts} list. Method returns {@link CompletionStage} of {@link ChannelsMap} that
     * contains cache of existing {@code supplyChannels}. If there is no need to create missing supply channels then
     * cache is built from passed {@code channelsMapBuilder}. Otherwise {@code channelsMapBuilder} is filled with
     * newly created channels and then built.
     *
     * @param drafts {@link List} containing {@link InventoryEntryDraft} objects where missing supply channels can occur
     * @param channelsMapBuilder builder of cache of existing {@code supplyChannels}
     * @return {@link CompletionStage} of {@link ChannelsMap} that contains cache of existing {@code supplyChannels}
     */
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
    private CompletionStage<ChannelsMap> createMissingSupplyChannels(@Nonnull final List<InventoryEntryDraft> drafts,
                                                                     @Nonnull final ChannelsMap.Builder
                                                                         channelsMapBuilder) {
        if (syncOptions.shouldEnsureChannels()) {
            final ChannelsMap existingChannels = channelsMapBuilder.build();
            final List<String> missingChannelsKeys = drafts.stream()
                .map(SkuChannelKeyTuple::of)
                .map(SkuChannelKeyTuple::getKey)
                .distinct()
                .filter(Objects::nonNull)
                .filter(key -> !existingChannels.getChannelId(key).isPresent())
                .collect(toList());
            final List<CompletableFuture<Channel>> creationStages = missingChannelsKeys.stream()
                .map(this::createMissingSupplyChannel)
                .map(newChannelStage -> newChannelStage
                    .handle((newChannel, exception) -> {
                        if (newChannel != null) {
                            channelsMapBuilder.add(newChannel);
                        }
                        return newChannel;
                    }))
                .map(CompletionStage::toCompletableFuture)
                .collect(toList());
            return CompletableFuture
                .allOf(creationStages.toArray(new CompletableFuture[creationStages.size()]))
                .thenApply(v -> channelsMapBuilder.build());
        } else {
            return CompletableFuture.completedFuture(channelsMapBuilder.build());
        }
    }

    /**
     * Fetches existing {@link InventoryEntry} objects from CTP project that correspond to passed {@code batchOfDrafts}.
     * Having existing inventory entries fetched, {@code batchOfDrafts} is compared and synced with fetched objects by
     * {@link InventorySync#compareAndSync(Map, List, ChannelsMap)} function. When fetching existing inventory entries
     * results in exception then error callback is executed and {@code batchOfDrafts} isn't processed.

     * @param batchOfDrafts batch of drafts that need to be synced
     * @param channelsMap cache of existing {@code supplyChannels}
     * @return {@link CompletionStage} of {@link Void} that indicates method progress, that may contain
     *      {@link SyncProblemException} when fetching existing inventory entries results in exception.
     */
    private CompletionStage<Void> processBatch(@Nonnull final List<InventoryEntryDraft> batchOfDrafts,
                                               @Nonnull final ChannelsMap channelsMap) {
        return fetchExistingInventories(batchOfDrafts, channelsMap)
            .exceptionally(exception -> {
                syncOptions.applyErrorCallback(format(CTP_INVENTORY_FETCH_FAILED, extractSkus(batchOfDrafts)),
                    exception);
                throw new SyncProblemException();
            })
            .thenCompose(existingInventories -> compareAndSync(existingInventories, batchOfDrafts, channelsMap));
    }

    /**
     * For each draft from {@code drafts} it checks if there is corresponding entry in {@code existingInventories},
     * and then either attempts to update such entry with data from draft or attempts to create new entry from draft.
     * After comparision and performing action "processed" and other relevant counter from statistics is incremented.
     * Method returns {@link CompletionStage} of {@link Void} that indicates all possible creation/update attempts
     * progress.
     *
     * @param existingInventories mapping of {@link SkuChannelKeyTuple} to {@link InventoryEntry} of instances existing
     *                            in a CTP project
     * @param drafts drafts that need to be synced
     * @param channelsMap cache of existing {@code supplyChannels}
     * @return {@link CompletionStage} of {@link Void} that indicates all possible creation/update attempts progress.
     */
    private CompletionStage<Void> compareAndSync(@Nonnull final Map<SkuChannelKeyTuple, InventoryEntry>
                                                     existingInventories,
                                                 @Nonnull final List<InventoryEntryDraft> drafts,
                                                 @Nonnull final ChannelsMap channelsMap) {
        final List<CompletableFuture<Void>> futures = new ArrayList<>(drafts.size());
        drafts.forEach(draft -> {
            final Optional<InventoryEntryDraft> fixedDraft = replaceChannelReference(draft, channelsMap);
            if (fixedDraft.isPresent()) {
                final SkuChannelKeyTuple skuKeyOfDraft = SkuChannelKeyTuple.of(draft);
                if (existingInventories.containsKey(skuKeyOfDraft)) {
                    final InventoryEntry existingEntry = existingInventories.get(skuKeyOfDraft);
                    futures.add(attemptUpdate(existingEntry, fixedDraft.get())
                        .thenAccept(updatedChannel -> {
                            if (!updatedChannel.equals(existingEntry)) {
                                statistics.incrementUpdated();
                            }
                        })
                        .exceptionally(exception -> {
                            statistics.incrementFailed();
                            syncOptions.applyErrorCallback(format(CTP_INVENTORY_ENTRY_UPDATE_FAILED, draft.getSku(),
                                SkuChannelKeyTuple.of(draft).getKey()), exception);
                            return null;
                        })
                        .toCompletableFuture());
                } else {
                    futures.add(inventoryService.createInventoryEntry(fixedDraft.get())
                        .thenAccept(createdEntry -> statistics.incrementCreated())
                        .exceptionally(exception -> {
                            statistics.incrementFailed();
                            syncOptions.applyErrorCallback(format(CTP_INVENTORY_ENTRY_CREATE_FAILED, draft.getSku(),
                                SkuChannelKeyTuple.of(draft).getKey()), exception);
                            return null;
                        })
                        .toCompletableFuture());
                }
            } else {
                statistics.incrementFailed();
            }
            statistics.incrementProcessed();
        });
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
    }

    /**
     * Returns {@link CompletionStage} instance which may contain mapping of {@link SkuChannelKeyTuple} to
     * {@link InventoryEntry} of instances existing in a CTP project. Instances are fetched from API by skus, that
     * corresponds to skus present in {@code drafts}. If fetching existing instances results in exception then
     * returned {@link CompletionStage} contains such exception.
     *
     * @param drafts {@link List} of drafts
     * @param channelsMap cache of existing {@code supplyChannels}
     * @return {@link CompletionStage} instance which contains either {@link Map} of {@link SkuChannelKeyTuple} to
     *      {@link InventoryEntry} of instances existing in a CTP project that correspond to passed {@code drafts} by
     *      sku comparision, or exception occurred during fetching existing inventory entries
     */
    private CompletionStage<Map<SkuChannelKeyTuple, InventoryEntry>> fetchExistingInventories(@Nonnull final
                                                                                              List<InventoryEntryDraft>
                                                                                                  drafts,
                                                                                              @Nonnull final ChannelsMap
                                                                                              channelsMap) {
        final Set<String> skus = extractSkus(drafts);
        return inventoryService.fetchInventoryEntriesBySkus(skus)
            .thenApply(existingEntries -> {
                final Map<SkuChannelKeyTuple, InventoryEntry> result = new HashMap<>();
                existingEntries.forEach(inventoryEntry -> {
                    final Optional<SkuChannelKeyTuple> tupleOptional = SkuChannelKeyTuple
                        .of(inventoryEntry, channelsMap);
                    if (tupleOptional.isPresent()) {
                        result.put(tupleOptional.get(), inventoryEntry);
                    }
                });
                return result;
            });
    }

    /**
     * Returns distinct SKUs present in {@code inventories}.
     *
     * @param inventories {@link List} of {@link InventoryEntryDraft} where each draft contains its sku
     * @return {@link Set} of distinct SKUs found in {@code inventories}.
     */
    private Set<String> extractSkus(final List<InventoryEntryDraft> inventories) {
        return inventories.stream()
                .map(InventoryEntryDraft::getSku)
                .collect(Collectors.toSet());
    }

    /**
     * Tries to update {@code entry} in CTP project with data from {@code draft}.
     * It calculates list of {@link UpdateAction} and calls API only when there is a need.
     * If there is no need to call API it returns {@link CompletionStage} with passed {@code entry}.
     * Otherwise it updates inventory entry and returns {@link CompletionStage} that may contain either updated
     * {@link InventoryEntry}, or exception.
     *
     * @param entry entry from existing system that could be updated.
     * @param draft draft containing data that could differ from data in {@code entry}.
     *              <strong>Sku isn't compared</strong>
     * @return {@link CompletionStage} of {@link InventoryEntry} that may contain passed {@code entry}, updated
     *      {@link InventoryEntry}, or exception
     */
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
    private CompletionStage<InventoryEntry> attemptUpdate(final InventoryEntry entry, final InventoryEntryDraft draft) {
        final List<UpdateAction<InventoryEntry>> updateActions =
            InventorySyncUtils.buildActions(entry, draft, syncOptions, typeService);
        if (!updateActions.isEmpty()) {
            return inventoryService.updateInventoryEntry(entry, updateActions);
        }
        return CompletableFuture.completedFuture(entry);
    }

    /**
     * Returns {@link Optional} that may contain {@link InventoryEntryDraft}. The payload of optional would be:
     * <ul>
     *     <li>Same {@code draft} instance if it has no reference to supply channel</li>
     *     <li>New {@link InventoryEntryDraft} instance if {@code draft} contains reference to supply channel.
     *     New instance would have same values as {@code draft} except for supply channel reference. Reference
     *     will be replaced with reference that points to ID of existing channel for key given in draft.</li>
     *     <li>Empty if supply channel for key wasn't found in {@code channelsMap}</li>
     * </ul>
     *
     * @param draft inventory entry draft from processed list
     * @param channelsMap mapping of supply channel key to supply channel Id for supply channels existing in
     *                             CTP project.
     * @return {@link Optional} with draft that is prepared to being created or compared with existing
     * {@link InventoryEntry}, or empty optional if method fails to find supply channel ID that should be referenced
     */
    private Optional<InventoryEntryDraft> replaceChannelReference(final InventoryEntryDraft draft,
                                                                  final ChannelsMap channelsMap) {
        final String supplyChannelKey = SkuChannelKeyTuple.of(draft).getKey();
        if (supplyChannelKey != null) {
            if (channelsMap.getChannelId(supplyChannelKey).isPresent()) {
                return Optional.of(withSupplyChannel(draft, channelsMap.getChannelId(supplyChannelKey).get()));
            } else {
                syncOptions.applyErrorCallback(format(CHANNEL_KEY_MAPPING_DOESNT_EXIST, supplyChannelKey), null);
                return Optional.empty();
            }
        }
        return Optional.of(draft);
    }

    /**
     * Returns new {@link InventoryEntryDraft} containing same data as {@code draft} except for
     * supply channel reference that is replaced by reference pointing to {@code supplyChannelId}.
     *
     * @param draft           draft where reference should be replaced
     * @param supplyChannelId ID of supply channel existing in target project
     * @return {@link InventoryEntryDraft} with supply channel reference pointing to {@code supplyChannelId}
     *      and other data same as in {@code draft}
     */
    private InventoryEntryDraft withSupplyChannel(@Nonnull final InventoryEntryDraft draft,
                                                  @Nonnull final String supplyChannelId) {
        final Reference<Channel> supplyChannelRef = Channel.referenceOfId(supplyChannelId);
        return InventoryEntryDraftBuilder.of(draft)
            .supplyChannel(supplyChannelRef)
            .build();
    }

    /**
     * Method tries to create supply channel of given {@code supplyChannelKey} in CTP project.
     * If operation succeed then {@link CompletionStage} with created {@link Channel} is returned, otherwise
     * error callback function is executed and {@link CompletionStage} with {@link SyncProblemException} is returned.
     *
     * @param supplyChannelKey key of supply channel that seems to not exists in a system
     * @return {@link CompletionStage} instance that contains either created {@link Channel} instance when succeed
     *      or {@link SyncProblemException} instance otherwise
     */
    private CompletionStage<Channel> createMissingSupplyChannel(@Nonnull final String supplyChannelKey) {
        return inventoryService.createSupplyChannel(supplyChannelKey)
            .exceptionally(exception -> {
                syncOptions.applyErrorCallback(format(CTP_CHANNEL_CREATE_FAILED, supplyChannelKey), exception);
                throw new SyncProblemException();
            });
    }
}
