/*
Changed by ywwynm

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

public class One extends Figure {

//    private static final float[][] POINTS = {
//            { 0.425414364640884f, 0.113259668508287f },
//            { 0.425414364640884f, 0.113259668508287f },
//            { 0.577348066298343f, 0.113259668508287f },
//            { 0.577348066298343f, 0.113259668508287f },
//            { 0.577348066298343f, 0.113259668508287f },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 },
//            { 0.577348066298343f, 1f                 }
//    };

//    private static final float[][] POINTS = {
//            { 0.40f, 0.11f },
//            { 0.40f, 0.11f },
//            { 0.57f, 0.11f },
//            { 0.57f, 0.11f },
//            { 0.57f, 0.11f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f },
//            { 0.57f, 1.00f }
//    };

    private static final float[][] POINTS = {
            { 0.40f, 0.11f },
            { 0.40f, 0.11f },
            { 0.57f, 0.11f },
            { 0.57f, 0.11f },
            { 0.57f, 0.11f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f },
            { 0.57f, 1.05f }
    };

    private static One INSTANCE = new One();

    protected One() {
        super(POINTS);
    }

    public static One getInstance() {
        return INSTANCE;
    }
}