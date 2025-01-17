// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

class TransformRunnable : Runnable {
    private var isComplete: Int? = null

    fun exitCode(i: Int) {
        isComplete = i
    }

    override fun run() {
        // do nothing
    }

    fun isComplete(): Int? = isComplete
}
