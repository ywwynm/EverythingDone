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