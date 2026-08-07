package dev.ehsanfahad.darkchamber.core.model

/**
 * Module marker for `:core:model`.
 *
 * Placeholder until the real pure-Kotlin domain types (`VaultItem`, `Album`, `Note`,
 * MIME handling — see docs/engineering/MODULES.md §1) land in their own cycle. It exists
 * only so the module compiles across all targets and has something for the smoke test to
 * assert; delete it once real types arrive.
 */
public const val CORE_MODEL_MODULE: String = "core:model"
