/*
 *  Copyright (C) 2007-2011 VMWare, Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.tools.service.definitions;

import java.io.Serializable;
import java.util.Comparator;

/**
 * @author small
 * @version $Rev$ - $Date$
 * 
 */
public class OperationComparator implements Comparator<Operation>, Serializable {

    private static final long serialVersionUID = 1L;

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
     */
    public int compare(Operation o1, Operation o2) {

        int ret = o1.getName().compareTo(o2.getName());
        if (0 == ret) {
            ret = o1.getParameter().size() - o2.getParameter().size();
        }
        return ret;
    }
}