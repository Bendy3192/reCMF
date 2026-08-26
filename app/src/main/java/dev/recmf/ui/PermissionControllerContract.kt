/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.PermissionController

/**
 * Thin wrapper over Health Connect's own permission contract.
 *
 * It exists so [MainActivity] can register the launcher as a field — the contract has to
 * be created before `onCreate` returns, and constructing it inline in a lambda would be
 * too late.
 */
class PermissionControllerContract : ActivityResultContract<Set<String>, Set<String>>() {
    private val delegate = PermissionController.createRequestPermissionResultContract()

    override fun createIntent(context: Context, input: Set<String>): Intent =
        delegate.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Set<String> =
        delegate.parseResult(resultCode, intent)
}
