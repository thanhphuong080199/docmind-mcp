package com.docmind.confluence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.confluence.sync-enabled", havingValue = "true")
public class ConfluenceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSyncScheduler.class);

    private final ConfluenceSyncService syncService;

    public ConfluenceSyncScheduler(ConfluenceSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${docmind.confluence.sync-interval:PT1H}")
    public void syncAll() {
        for (String spaceKey : syncService.configuredSpaceKeys()) {
            try {
                ConfluenceSyncService.SyncResult r = syncService.sync(spaceKey);
                log.info("Scheduled sync of {}: {} ingested, {} skipped, {} failed, {} removed",
                        r.spaceKey(), r.ingested(), r.skipped(), r.failed(), r.removed());
            }
            catch (RuntimeException e) {
                log.warn("Scheduled sync of space {} failed: {}", spaceKey, e.getMessage());
            }
        }
    }
}
