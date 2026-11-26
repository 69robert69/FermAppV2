package ro.fermapp.domain.copilot

import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.localeCompare

/**
 * Task types the copilot plans. Codes already used by the To-Do list keep their
 * meaning, so AI tasks behave exactly like tasks created by hand.
 */
data class CopilotTaskType(
    val label: String,
    val category: TaskCategory,
    /** Machine types that can carry the work out, best first. */
    val machines: List<String>,
)

private fun type(label: String, category: TaskCategory, vararg machines: String) = CopilotTaskType(label, category, machines.toList())

val COPILOT_TASK_TYPES: Map<String, CopilotTaskType> = linkedMapOf(
    "PLOWING" to type("Plowing", TaskCategory.TILLAGE, "TRACTOR"),
    "DISKING" to type("Disking", TaskCategory.TILLAGE, "DISC", "TRACTOR"),
    "SUBSOILING" to type("Subsoiling", TaskCategory.TILLAGE, "SUBSOILER", "TRACTOR"),
    "CHISELING" to type("Chiseling", TaskCategory.TILLAGE, "CULTIVATOR", "TRACTOR"),
    "STRIP_TILL" to type("Strip-Till", TaskCategory.TILLAGE, "CULTIVATOR", "TRACTOR"),
    "SOIL_PREP" to type("Seedbed preparation", TaskCategory.TILLAGE, "CULTIVATOR", "DISC", "TRACTOR"),
    "SEED_PURCHASE" to type("Seed purchase", TaskCategory.PLANTING),
    "SOWING" to type("Sowing", TaskCategory.PLANTING, "SEEDER", "TRACTOR"),
    "FERTILIZATION" to type("Base fertilization", TaskCategory.FERTILIZATION, "TRACTOR"),
    "NITROGEN" to type("Nitrogen fertilizer", TaskCategory.FERTILIZATION, "TRACTOR"),
    "FOLIAR_FEEDING" to type("Foliar feeding", TaskCategory.FERTILIZATION, "SPRAYER", "TRACTOR"),
    "HERBICIDE" to type("Herbicide application", TaskCategory.CROP_PROTECTION, "SPRAYER", "TRACTOR"),
    "FUNGICIDE" to type("Fungicide application", TaskCategory.CROP_PROTECTION, "SPRAYER", "TRACTOR"),
    "INSECTICIDE" to type("Insecticide application", TaskCategory.CROP_PROTECTION, "SPRAYER", "TRACTOR"),
    "IRRIGATION_START" to type("Start irrigation", TaskCategory.IRRIGATION),
    "IRRIGATION_STOP" to type("Stop irrigation", TaskCategory.IRRIGATION),
    "IRRIGATION_CHECK" to type("Irrigation check", TaskCategory.IRRIGATION),
    "MOISTURE_CHECK" to type("Moisture check", TaskCategory.IRRIGATION),
    "CROP_SCOUTING" to type("Crop scouting", TaskCategory.MONITORING),
    "FIELD_INSPECTION" to type("Field inspection", TaskCategory.MONITORING),
    "DISEASE_CHECK" to type("Disease inspection", TaskCategory.MONITORING),
    "PEST_CHECK" to type("Pest check", TaskCategory.MONITORING),
    "WEED_MONITORING" to type("Weed monitoring", TaskCategory.MONITORING),
    "EMERGENCE_CHECK" to type("Emergence check", TaskCategory.MONITORING),
    "LEAF_ANALYSIS" to type("Leaf analysis", TaskCategory.MONITORING),
    "SOIL_SAMPLING" to type("Soil sampling", TaskCategory.MONITORING),
    "HARVEST_PREP" to type("Harvest preparation", TaskCategory.HARVEST),
    "HARVEST" to type("Harvest", TaskCategory.HARVEST, "COMBINE"),
    "GRAIN_TRANSPORT" to type("Grain transport", TaskCategory.HARVEST, "TRAILER", "TRACTOR"),
)

val CATEGORY_LABELS: Map<TaskCategory, String> = mapOf(
    TaskCategory.TILLAGE to "Tillage",
    TaskCategory.PLANTING to "Planting",
    TaskCategory.FERTILIZATION to "Fertilization",
    TaskCategory.CROP_PROTECTION to "Crop protection",
    TaskCategory.IRRIGATION to "Irrigation",
    TaskCategory.MONITORING to "Monitoring",
    TaskCategory.HARVEST to "Harvest",
)

fun taskCategory(type: String): TaskCategory? = COPILOT_TASK_TYPES[type]?.category

/** Words that name a kind of work, used to find the tasks a request refers to. */
val CATEGORY_WORDS: Map<TaskCategory, List<String>> = linkedMapOf(
    TaskCategory.TILLAGE to listOf("tillage", "cultivation", "cultivating", "plow", "plough", "disk", "disc", "subsoil", "chisel", "seedbed", "arat", "aratura", "discuit", "scarific", "pregatire"),
    TaskCategory.PLANTING to listOf("sowing", "sow", "planting", "plant", "seeding", "semanat", "semana", "insamantare"),
    TaskCategory.FERTILIZATION to listOf("fertiliz", "fertilis", "nitrogen", "urea", "npk", "foliar", "manure", "ingrasa", "azot", "uree", "fertilizare"),
    TaskCategory.CROP_PROTECTION to listOf("spray", "herbicide", "fungicide", "insecticide", "protection", "stropi", "erbicid", "tratament", "fungicid", "insecticid"),
    TaskCategory.IRRIGATION to listOf("irrigat", "watering", "irigat", "udat"),
    TaskCategory.MONITORING to listOf("scouting", "inspection", "monitor", "check", "sampling", "inspectie", "verificare", "monitorizare"),
    TaskCategory.HARVEST to listOf("harvest", "combine", "recolt", "treier"),
)

private fun busy(snapshot: FarmSnapshot, day: String, pick: (CopilotTask) -> String?): Set<String> =
    snapshot.tasks
        .filter { it.status != CopilotTaskStatus.DONE && it.dueDate.take(10) == day }
        .mapNotNull(pick)
        .toSet()

/** A machine of the right type that is not running and has nothing else that day. */
fun suggestMachine(snapshot: FarmSnapshot, type: String, day: String): String? {
    val wanted = COPILOT_TASK_TYPES[type]?.machines ?: emptyList()
    if (wanted.isEmpty()) return null
    val taken = busy(snapshot, day) { it.machineId }
    val running = snapshot.runs.filter { it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED }.map { it.machineId }.toSet()
    for (machineType in wanted) {
        val free = snapshot.machines
            .filter { it.type == machineType && it.id !in taken && it.id !in running }
            .sortedByDescending { it.powerHp ?: 0.0 }
            .firstOrNull()
        if (free != null) return free.id
    }
    return null
}

/** The operator with the fewest open tasks that week; field work goes to machine operators first. */
fun suggestOperator(snapshot: FarmSnapshot, type: String, day: String): String? {
    val fieldWork = (COPILOT_TASK_TYPES[type]?.machines?.size ?: 0) > 0
    val pool = snapshot.members.filter {
        if (fieldWork) it.role == MemberRole.OPERATOR else it.role == MemberRole.AGRONOMIST || it.role == MemberRole.OPERATOR
    }
    val candidates = pool.ifEmpty { snapshot.members.filter { it.role == MemberRole.OPERATOR } }
    if (candidates.isEmpty()) return null
    val weekEnd = addDays(day, 6)
    fun load(id: String) = snapshot.tasks.count {
        it.assigneeId == id && it.status != CopilotTaskStatus.DONE && it.dueDate.take(10) >= day && it.dueDate.take(10) <= weekEnd
    }
    return candidates.sortedWith(compareBy<CopilotMember> { load(it.id) }.then { a, b -> localeCompare(a.name, b.name) }).first().id
}

private var sequence = 0

data class ProposalInput(
    val type: String,
    val parcelId: String?,
    val dueDate: String,
    val priority: CopilotPriority,
    val reason: String,
    /** Days the work can move without harm, after the due date. */
    val windowDays: Int = 3,
    val title: String? = null,
    /** Planned after this task: not given for follow-ups themselves. */
    val tillage: TillageSystem? = null,
    val withFollowUps: Boolean = true,
)

fun propose(snapshot: FarmSnapshot, input: ProposalInput): TaskProposal {
    val parcel = snapshot.parcels.firstOrNull { it.id == input.parcelId }
    val kind = COPILOT_TASK_TYPES[input.type]
    val label = kind?.label ?: input.type
    val due = input.dueDate.take(10)
    val proposal = TaskProposal(
        key = "p${++sequence}",
        title = input.title ?: if (parcel != null) "$label – ${parcel.name}" else label,
        type = input.type,
        category = kind?.category ?: TaskCategory.MONITORING,
        parcelId = input.parcelId,
        dueDate = due,
        window = DateWindow(due, addDays(due, input.windowDays)),
        priority = input.priority,
        reason = input.reason,
        assigneeId = suggestOperator(snapshot, input.type, due),
        machineId = suggestMachine(snapshot, input.type, due),
    )
    if (!input.withFollowUps) return proposal
    return proposal.copy(followUps = followUpsFor(snapshot, proposal.type, proposal.parcelId, proposal.dueDate, input.tillage ?: parcel?.tillage))
}

/** The work that naturally comes next, so planning continues after a task is created. */
fun followUpsFor(snapshot: FarmSnapshot, type: String, parcelId: String?, dueDate: String, tillage: TillageSystem?): List<TaskProposal> {
    fun next(nextType: String, days: Int, priority: CopilotPriority, reason: String, windowDays: Int = 5) =
        propose(snapshot, ProposalInput(nextType, parcelId, addDays(dueDate, days), priority, reason, windowDays, withFollowUps = false))

    return when (type) {
        "HARVEST" -> {
            val steps = mutableListOf(
                next("GRAIN_TRANSPORT", 0, CopilotPriority.HIGH, "Trailers must be ready when the combine starts, so grain does not wait in the field.", 1),
                next("SOIL_SAMPLING", 3, CopilotPriority.MEDIUM, "Sampling right after harvest gives the nutrient balance for next season’s fertilization plan.", 10),
            )
            val step = when (tillage ?: TillageSystem.MINIMUM_TILL) {
                TillageSystem.CONVENTIONAL -> Triple("PLOWING", 7, "Autumn plowing after harvest buries the residue and lets frost break the clods.")
                TillageSystem.MINIMUM_TILL -> Triple("CHISELING", 5, "Minimum-till preparation: a shallow chisel pass mixes the residue without inverting the soil.")
                TillageSystem.STRIP_TILL -> Triple("STRIP_TILL", 10, "Autumn strip-till lets the strips settle and warm faster in spring.")
                TillageSystem.NO_TILL -> null
            }
            if (step != null) {
                steps.add(next(step.first, step.second, CopilotPriority.MEDIUM, step.third, 10))
            } else {
                steps.add(next("FIELD_INSPECTION", 5, CopilotPriority.LOW, "No Till: check that the residue is spread evenly before direct sowing.", 10))
            }
            steps
        }
        "SOWING" -> listOf(
            next("EMERGENCE_CHECK", 12, CopilotPriority.MEDIUM, "Counting plants after emergence shows whether the stand needs re-sowing."),
            next("WEED_MONITORING", 21, CopilotPriority.MEDIUM, "Early weed control protects yield the most in the first weeks."),
        )
        "HERBICIDE", "FUNGICIDE", "INSECTICIDE" ->
            listOf(next("FIELD_INSPECTION", 7, CopilotPriority.LOW, "An efficacy check a week after the treatment shows whether a second pass is needed."))
        "FERTILIZATION", "NITROGEN" ->
            listOf(next("CROP_SCOUTING", 14, CopilotPriority.LOW, "Scouting two weeks later shows how the crop responded to the nitrogen."))
        "IRRIGATION_START" ->
            listOf(next("IRRIGATION_CHECK", 2, CopilotPriority.MEDIUM, "Check the dose and uniformity after the first passes.", 2))
        "PLOWING", "DISKING", "SUBSOILING", "CHISELING" ->
            listOf(next("SOIL_PREP", 10, CopilotPriority.MEDIUM, "Seedbed preparation before sowing levels the field and breaks the remaining clods."))
        "SOIL_SAMPLING" ->
            listOf(next("FERTILIZATION", 14, CopilotPriority.MEDIUM, "Base fertilization adjusted to the soil analysis results.", 14))
        else -> emptyList()
    }
}

fun followUpsFor(snapshot: FarmSnapshot, task: TaskProposal, tillage: TillageSystem?) =
    followUpsFor(snapshot, task.type, task.parcelId, task.dueDate, tillage)
