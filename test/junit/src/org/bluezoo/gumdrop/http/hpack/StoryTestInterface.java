
package org.bluezoo.gumdrop.http.hpack;

import java.util.List;
import org.bluezoo.gumdrop.http.Header;

/**
 * Story test case
 */
interface StoryTestInterface {

    public void testCase(int seqno, String wire, List<Header> headers);

}
