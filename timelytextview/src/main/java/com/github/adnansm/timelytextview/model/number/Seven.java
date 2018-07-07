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

public class Seven extends Figure {

//    private static final float[][] POINTS = {
//            { 0.259668508287293f, 0.116022099447514f },
//            { 0.259668508287293f, 0.116022099447514f },
//
//            { 0.87292817679558f , 0.116022099447514f },
//            { 0.87292817679558f , 0.116022099447514f },
//            { 0.87292817679558f , 0.116022099447514f },
//
//            { 0.7f              , 0.422099447513812f },
//            { 0.7f              , 0.422099447513812f },
//            { 0.7f              , 0.422099447513812f },
//
//            { 0.477348066298343f, 0.733149171270718f },
//            { 0.477348066298343f, 0.733149171270718f },
//            { 0.477348066298343f, 0.733149171270718f },
//
//            { 0.25414364640884f , 1f                 },
//            { 0.25414364640884f , 1f                 }
//        };

//    private static final float[][] POINTS = {
//            { 0.24f            , 0.116022099447514f },
//            { 0.24f            , 0.116022099447514f },
//
//            { 0.87292817679558f, 0.116022099447514f },
//            { 0.87292817679558f, 0.116022099447514f },
//            { 0.87292817679558f, 0.116022099447514f },
//
//            { 0.6953268f       , 0.422099447513812f },
//            { 0.6953268f       , 0.422099447513812f },
//            { 0.6953268f       , 0.422099447513812f },
//
//            { 0.51484025f      , 0.733149171270718f },
//            { 0.51484025f      , 0.733149171270718f },
//            { 0.51484025f      , 0.733149171270718f },
//
//            { 0.36f            , 1f                 },
//            { 0.36f            , 1f                 }
//    };

    private static final float[][] POINTS = {
            { 0.24f, 0.12f },
            { 0.24f, 0.12f },
            { 0.86f, 0.12f },
            { 0.86f, 0.12f },
            { 0.86f, 0.12f },
            { 0.71f, 0.42f },
            { 0.71f, 0.42f },
            { 0.71f, 0.42f },
            { 0.56f, 0.72f },
            { 0.56f, 0.72f },
            { 0.56f, 0.72f },
            { 0.40f, 1.04f },
            { 0.40f, 1.04f }
    };


    private static Seven INSTANCE = new Seven();

    protected Seven() {
        super(POINTS);
    }

    public static Seven getInstance() {
        return INSTANCE;
    }
}