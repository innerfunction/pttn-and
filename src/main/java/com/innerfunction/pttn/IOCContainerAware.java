// Copyright 2016 InnerFunction Ltd.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
package com.innerfunction.pttn;

/**
 * An IOC component aware of its container.
 * Object's implementing this protocol will have their iocContainer property
 * set to the container that has instantiated them.
 *
 * Created by juliangoacher on 23/09/16.
 */
public interface IOCContainerAware {

    void setIOCContainer(Container container);

}
