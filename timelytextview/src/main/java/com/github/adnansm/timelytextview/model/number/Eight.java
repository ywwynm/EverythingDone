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

public class Eight extends Figure {
//    private static final float[][] POINTS = {
//            { 0.558011049723757f, 0.530386740331492f },
//            { 0.243093922651934f, 0.524861878453039f },
//            { 0.243093922651934f, 0.104972375690608f },
//            { 0.558011049723757f, 0.104972375690608f },
//            { 0.850828729281768f, 0.104972375690608f },
//            { 0.850828729281768f, 0.530386740331492f },
//            { 0.558011049723757f, 0.530386740331492f },
//            { 0.243093922651934f, 0.530386740331492f },
//            { 0.198895027624309f, 0.988950276243094f },
//            { 0.558011049723757f, 0.988950276243094f },
//            { 0.850828729281768f, 0.988950276243094f },
//            { 0.850828729281768f, 0.530386740331492f },
//            { 0.558011049723757f, 0.530386740331492f }
//    };

//    private static final float[][] POINTS = {
//            { 0.558011049723757f, 0.530386740331492f },
//            { 0.23f             , 0.524861878453039f },
//            { 0.23f             , 0.09f },
//            { 0.558011049723757f, 0.09f },
//            { 0.886022099447514f, 0.09f },
//            { 0.886022099447514f, 0.530386740331492f },
//            { 0.558011049723757f, 0.530386740331492f },
//            { 0.23f             , 0.530386740331492f },
//            { 0.16f             , 1.024f },
//            { 0.558011049723757f, 1.024f },
//            { 0.956022099447514f, 1.024f },
//            { 0.886022099447514f, 0.530386740331492f },
//            { 0.558011049723757f, 0.530386740331492f }
//    };

    private static final float[][] POINTS = {
            { 0.56f, 0.53f },
            { 0.23f, 0.52f },
            { 0.23f, 0.09f },
            { 0.56f, 0.09f },
            { 0.89f, 0.09f },
            { 0.89f, 0.53f },
            { 0.56f, 0.53f },
            { 0.23f, 0.53f },
            { 0.16f, 1.04f },
            { 0.56f, 1.04f },
            { 0.96f, 1.04f },
            { 0.89f, 0.53f },
            { 0.56f, 0.53f }
    };

    private static Eight INSTANCE = new Eight();

    protected Eight() {
        super(POINTS);
    }

    public static Eight getInstance() {
        return INSTANCE;
    }
}