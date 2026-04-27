package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting

/**
 * Test-only fake. HomeViewModelTest does not call fetchPlatformStatus, so most
 * methods throw. Override platforms when a test needs platform-name resolution.
 */
class FakeSettingRepository : SettingRepository {
    var platforms: List<PlatformV2> = emptyList()

    override suspend fun fetchPlatformV2s(): List<PlatformV2> = platforms

    override suspend fun fetchPlatforms(): List<Platform> = error("not used")
    override suspend fun fetchThemes(): ThemeSetting = error("not used")
    override suspend fun migrateToPlatformV2() = error("not used")
    override suspend fun updatePlatforms(platforms: List<Platform>) = error("not used")
    override suspend fun updateThemes(themeSetting: ThemeSetting) = error("not used")
    override suspend fun addPlatformV2(platform: PlatformV2) = error("not used")
    override suspend fun updatePlatformV2(platform: PlatformV2) = error("not used")
    override suspend fun deletePlatformV2(platform: PlatformV2) = error("not used")
    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = error("not used")
}
