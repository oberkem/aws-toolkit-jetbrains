// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.credentials.sono

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProvider
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProviderListener

class SonoLogoutAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = provider(e)?.supportsLogout() ?: false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val provider = provider(e) ?: return

        ApplicationManager.getApplication().messageBus.syncPublisher(BearerTokenProviderListener.TOPIC).invalidate(provider.id)
    }

    private fun provider(e: AnActionEvent): BearerTokenProvider? {
        val scm = CodeCatalystCredentialManager.getInstance(e.project)
        val connection = scm.connection() ?: return null
        return scm.provider(connection)
    }
}
