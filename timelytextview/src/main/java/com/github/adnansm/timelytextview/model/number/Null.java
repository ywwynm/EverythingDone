/*
Copyright 2014 Adnan A M.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package com.github.adnansm.timelytextview.model.number;


import com.github.adnansm.timelytextview.model.core.Figure;

public class Null extends Figure {
    private static final float[][] POINTS = {
            {0.5f, 0.5f},   {0.5f, 0.5f},   {0.5f, 0.5f},
            {0.5f, 0.5f},   {0.5f, 0.5f},   {0.5f, 0.5f},
            {0.5f, 0.5f},                   {0.5f, 0.5f},                   {0.5f, 0.5f},
            {0.5f, 0.5f},                   {0.5f, 0.5f},                   {0.5f, 0.5f},
            {0.5f, 0.5f}
    };

    private static final Null INSTANCE = new Null();

    protected Null() {
        super(POINTS);
    }

    public static Null getInstance() {
        return INSTANCE;
    }
}