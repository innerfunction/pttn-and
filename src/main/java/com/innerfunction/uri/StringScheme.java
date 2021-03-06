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
package com.innerfunction.uri;

import android.content.Context;
import android.net.Uri;

import java.util.Map;

import com.innerfunction.util.StringTemplate;

public class StringScheme implements URIScheme {

    private Context context;

    public StringScheme(Context context) {
        this.context = context;
    }

    @Override
    public Object dereference(CompoundURI uri, Map<String, Object> params) {
        String value = uri.getName();
        if( params.size() > 0 ) {
            // The URI name is treated as a string template to be populated with the parameter values.
            value = StringTemplate.render( value, params );
        }
        return Uri.decode( value );
    }
}
