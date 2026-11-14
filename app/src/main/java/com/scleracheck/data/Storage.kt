package com.scleracheck.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.scleracheck.colour.ColourCorrection
import kotlinx.coroutines.flow.Flow

/**
 * Which camera + light the check uses. Numbers from the two modes are NOT comparable (different
 * sensor, lens, and light source), so baselines and decisions are kept per mode.
 */
enum class CaptureMode(val code: Int, val label: String) {
    /** Default: patient holds the phone like a selfie and can see the screen; the screen is the light. */
    FRONT_SCREEN(1, "Front camera"),
    /** Helper mode: someone else points the back camera at the patient's eye; the LED torch is the light. */
    REAR_TORCH(0, "Back + helper");

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: FRONT_SCREEN }
}

/**
 * On-device storage only (CLAUDE.md §1.4): colour metrics and outcomes. No images are stored here.
 * (Cropped sclera patches are saved only when the developer toggle is on — see PatchSaver.)
 */
@Entity(tableName = "checks")
data class CheckRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** NO_CONCERN / REFER / RETAKE */
    val outcome: String,
    val bLeft: Double?,
    val bRight: Double?,
    val bMean: Double?,
    val delta: Double?,
    val aLeft: Double?,
    val aRight: Double?,
    val lLeft: Double?,
    val lRight: Double?,
    val retakeReasons: String,
    val isBaselineCapture: Boolean,
    val mode: Int = CaptureMode.FRONT_SCREEN.code,
)

@Entity(tableName = "baseline")
data class Baseline(
    /** = CaptureMode.code: one baseline per camera mode. */
    @PrimaryKey val id: Int,
    val timestamp: Long,
    val bStar: Double,
    /** True if the GP said the patient IS currently jaundiced (monitoring baseline, CLAUDE.md §2A.2). */
    val monitoring: Boolean,
    /** SD of the captures the baseline was built from (BaselineBuilder), null for legacy single captures. */
    val sd: Double? = null,
    val captures: Int = 1,
)

@Dao
interface CheckDao {
    @Insert suspend fun insert(r: CheckRecord): Long
    @Query("SELECT * FROM checks ORDER BY timestamp ASC") fun all(): Flow<List<CheckRecord>>
    @Query("SELECT * FROM checks WHERE outcome != 'RETAKE' AND isBaselineCapture = 0 AND mode = :mode ORDER BY timestamp ASC")
    suspend fun validChecks(mode: Int): List<CheckRecord>
    @Query("SELECT * FROM checks WHERE outcome != 'RETAKE' AND isBaselineCapture = 0 ORDER BY timestamp DESC LIMIT 1")
    fun lastValid(): Flow<CheckRecord?>
    @Query("DELETE FROM checks") suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setBaseline(b: Baseline)
    @Query("SELECT * FROM baseline WHERE id = :mode") suspend fun baseline(mode: Int): Baseline?
    @Query("SELECT * FROM baseline WHERE id = :mode") fun baselineFlow(mode: Int): Flow<Baseline?>
    @Query("DELETE FROM baseline") suspend fun clearBaseline()
}

@Database(entities = [CheckRecord::class, Baseline::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CheckDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "scleracheck.db")
                // Hackathon build: schema changes wipe local test data rather than migrating it.
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}

/** Small settings. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("scleracheck", Context.MODE_PRIVATE)

    var disclaimerAccepted: Boolean
        get() = p.getBoolean("disclaimer", false)
        set(v) = p.edit().putBoolean("disclaimer", v).apply()

    /** Developer toggle (CLAUDE.md §9): live numbers + overlay + saving cropped patches. Off by default. */
    var devMode: Boolean
        get() = p.getBoolean("dev", false)
        set(v) = p.edit().putBoolean("dev", v).apply()

    var captureMode: CaptureMode
        get() = CaptureMode.of(p.getInt("mode", CaptureMode.FRONT_SCREEN.code))
        set(v) = p.edit().putInt("mode", v.code).apply()

    var checkFrequencyDays: Int
        get() = p.getInt("freq", 1)
        set(v) = p.edit().putInt("freq", v).apply()

    var ccm: ColourCorrection
        get() = ColourCorrection.deserialise(p.getString("ccm", null))
        set(v) = p.edit().putString("ccm", v.serialise()).apply()
}
