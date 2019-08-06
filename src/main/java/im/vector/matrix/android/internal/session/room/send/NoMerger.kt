/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.internal.session.room.send

import androidx.work.Data
import androidx.work.InputMerger

/**
 * InputMerger which takes only the first input, to ensure an appended work will only have the specified parameters
 */
internal class NoMerger : InputMerger() {
    override fun merge(inputs: MutableList<Data>): Data {
        return inputs.first()
    }
}