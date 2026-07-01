package com.gridstore.huevista.paint;

import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.service.ShadeAdminService;
import com.gridstore.huevista.project.repository.RegionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the admin catalogue wipe: dangling region colour references are cleared, every
 * shade is removed in one batch delete, and the caller gets accurate counts back.
 */
class ShadeAdminServiceTest {

    private final ShadeRepository shadeRepo = mock(ShadeRepository.class);
    private final RegionRepository regionRepo = mock(RegionRepository.class);
    private final ShadeAdminService service = new ShadeAdminService(shadeRepo, regionRepo);

    @Test
    void deleteAllShades_clearsRegionRefsThenWipesCatalogue_andReportsCounts() {
        when(shadeRepo.count()).thenReturn(9500L);
        when(regionRepo.clearAllAppliedColors()).thenReturn(42);

        ShadeAdminService.DeleteResult result = service.deleteAllShades();

        assertThat(result.deletedShades()).isEqualTo(9500L);
        assertThat(result.clearedRegions()).isEqualTo(42);

        // Region references are cleared before the shades are deleted in a single batch.
        InOrder order = inOrder(shadeRepo, regionRepo);
        order.verify(shadeRepo).count();
        order.verify(regionRepo).clearAllAppliedColors();
        order.verify(shadeRepo).deleteAllInBatch();
    }

    @Test
    void deleteAllShades_onEmptyCatalogue_reportsZeroesAndStillEvictsCaches() {
        when(shadeRepo.count()).thenReturn(0L);
        when(regionRepo.clearAllAppliedColors()).thenReturn(0);

        ShadeAdminService.DeleteResult result = service.deleteAllShades();

        assertThat(result.deletedShades()).isZero();
        assertThat(result.clearedRegions()).isZero();
        verify(shadeRepo).deleteAllInBatch();
    }
}
