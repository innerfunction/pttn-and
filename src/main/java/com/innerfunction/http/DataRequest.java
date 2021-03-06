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
package com.innerfunction.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.util.Arrays;

/**
 * An HTTP data request.
 * The data payload in the server response should be small enough to fit in memory.
 *
 * Attached by juliangoacher on 09/07/16.
 */
public class DataRequest extends Request {

    public DataRequest(String url, String method) throws MalformedURLException {
        super( url, method );
    }

    @Override
    Response readResponse(HttpURLConnection connection) throws IOException {
        InputStream in = openInputStream( connection );
        checkForNetworkSignon( connection );
        byte[] body = new byte[4096];
        int offset = 0;
        while( true ) {
            // Read available data into the buffer.
            int read = in.read( body, offset, body.length - offset );
            if( read > 0 ) {
                // Update read offset into buffer.
                offset += read;
                // If less than 4k free space left in the buffer then double its size.
                if( body.length - offset < 4096 ) {
                    body = Arrays.copyOf( body, body.length * 2 );
                }
            }
            else break; // No bytes read => end of content.
        }
        // Trim array body back to content size.
        body = Arrays.copyOf( body, offset );
        return new Response( getURL(), connection, body );
    }

}
