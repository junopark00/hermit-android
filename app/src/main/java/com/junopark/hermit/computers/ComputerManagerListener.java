package com.junopark.hermit.computers;

import com.junopark.hermit.nvstream.http.ComputerDetails;

public interface ComputerManagerListener {
    void notifyComputerUpdated(ComputerDetails details);
}
