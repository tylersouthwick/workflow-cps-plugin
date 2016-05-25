/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.graphanalysis;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Assert;

import java.util.Collection;
import java.util.HashMap;

// Slightly dirty but it removes a ton of FlowTestUtils.* class qualifiers
import static org.jenkinsci.plugins.workflow.graphanalysis.FlowTestUtils.*;

/**
 * Tests for internals of ForkScanner
 */
public class ForkScannerTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    /** Flow structure (ID - type)
     2 - FlowStartNode (BlockStartNode)
     3 - Echostep
     4 - ParallelStep (StepStartNode) (start branches)
     6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
     7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
     8 - EchoStep, (branch 1) parent=6
     9 - StepEndNode, (end branch 1) startId=6, parentId=8
     10 - EchoStep, (branch 2) parentId=7
     11 - EchoStep, (branch 2) parentId = 10
     12 - StepEndNode (end branch 2)  startId=7  parentId=11,
     13 - StepEndNode (close branches), parentIds = 9,12, startId=4
     14 - EchoStep
     15 - FlowEndNode (BlockEndNode)
     */
    WorkflowRun SIMPLE_PARALLEL_RUN;
    WorkflowRun NESTED_PARALLEL_RUN;

    @Before
    public void setUp() throws Exception {
        r.jenkins.getInjector().injectMembers(this);

        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "SimpleParallel");
        job.setDefinition(new CpsFlowDefinition(
                "echo 'first'\n" +
                        "def steps = [:]\n" +
                        "steps['1'] = {\n" +
                        "    echo 'do 1 stuff'\n" +
                        "}\n" +
                        "steps['2'] = {\n" +
                        "    echo '2a'\n" +
                        "    echo '2b'\n" +
                        "}\n" +
                        "parallel steps\n" +
                        "echo 'final'"
        ));
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        this.SIMPLE_PARALLEL_RUN = b;

        job = r.jenkins.createProject(WorkflowJob.class, "NestedParallel");
        job.setDefinition(new CpsFlowDefinition(
                "echo 'first'\n" +
                        "def steps = [:]\n" +
                        "steps['1'] = {\n" +
                        "    echo 'do 1 stuff'\n" +
                        "}\n" +
                        "steps['2'] = {\n" +
                        "    echo '2a'\n" +
                        "    def nested = [:]\n" +
                        "    nested['2-1'] = {\n" +
                        "        echo 'do 2-1'\n" +
                        "    } \n" +
                        "    nested['2-2'] = {\n" +
                        "        sleep 1\n" +
                        "        echo '2 section 2'\n" +
                        "    }\n" +
                        "    echo '2b'\n" +
                        "    parallel nested\n" +
                        "}\n" +
                        "parallel steps\n" +
                        "echo 'final'"
        ));
        b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        this.NESTED_PARALLEL_RUN = b;
    }

    @Test
    public void testForkedScanner() throws Exception {
        FlowExecution exec = SIMPLE_PARALLEL_RUN.getExecution();
        Collection<FlowNode> heads =  SIMPLE_PARALLEL_RUN.getExecution().getCurrentHeads();

        // Initial case
        ForkScanner scanner = new ForkScanner();
        scanner.setup(heads, null);
        Assert.assertNull(scanner.currentParallelStart);
        Assert.assertNull(scanner.currentParallelStartNode);
        Assert.assertNotNull(scanner.parallelBlockStartStack);
        Assert.assertEquals(0, scanner.parallelBlockStartStack.size());
        Assert.assertTrue(scanner.isWalkingFromFinish());

        // Fork case
        scanner.setup(exec.getNode("13"));
        Assert.assertFalse(scanner.isWalkingFromFinish());
        Assert.assertEquals("13", scanner.next().getId());
        Assert.assertNotNull(scanner.parallelBlockStartStack);
        Assert.assertEquals(0, scanner.parallelBlockStartStack.size());
        Assert.assertEquals(exec.getNode("4"), scanner.currentParallelStartNode);

        ForkScanner.ParallelBlockStart start = scanner.currentParallelStart;
        Assert.assertEquals(2, start.totalBranches);
        Assert.assertEquals(1, start.remainingBranches);
        Assert.assertEquals(1, start.unvisited.size());
        Assert.assertEquals(exec.getNode("4"), start.forkStart);

        Assert.assertEquals(exec.getNode("9"), scanner.next());
        Assert.assertEquals(exec.getNode("8"), scanner.next());
        Assert.assertEquals(exec.getNode("6"), scanner.next());
        FlowNode f = scanner.next();
        Assert.assertEquals(exec.getNode("12"), f);

        // Now we test the least common ancestor bits
    }

    @Test
    public void testFlowSegmentSplit() throws Exception {
        FlowExecution exec = SIMPLE_PARALLEL_RUN.getExecution();

        /** Flow structure (ID - type)
         2 - FlowStartNode (BlockStartNode)
         3 - Echostep
         4 - ParallelStep (StepStartNode) (start branches)
         6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
         7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
         8 - EchoStep, (branch 1) parent=6
         9 - StepEndNode, (end branch 1) startId=6, parentId=8
         10 - EchoStep, (branch 2) parentId=7
         11 - EchoStep, (branch 2) parentId = 10
         12 - StepEndNode (end branch 2)  startId=7  parentId=11,
         13 - StepEndNode (close branches), parentIds = 9,12, startId=4
         14 - EchoStep
         15 - FlowEndNode (BlockEndNode)
         */

        HashMap<FlowNode, ForkScanner.FlowPiece> nodeMap = new HashMap<FlowNode,ForkScanner.FlowPiece>();
        ForkScanner.FlowSegment mainBranch = new ForkScanner.FlowSegment();
        ForkScanner.FlowSegment sideBranch = new ForkScanner.FlowSegment();
        FlowNode BRANCH1_END = exec.getNode("9");
        FlowNode BRANCH2_END = exec.getNode("12");
        FlowNode START_PARALLEL = exec.getNode("4");

        // Branch 1, we're going to run one flownode beyond the start of the parallel branch and then split
        mainBranch.add(BRANCH1_END);
        mainBranch.add(exec.getNode("8"));
        mainBranch.add(exec.getNode("6"));
        mainBranch.add(exec.getNode("4"));
        mainBranch.add(exec.getNode("3"));  // FlowNode beyond the fork point
        for (FlowNode f : mainBranch.visited) {
            nodeMap.put(f, mainBranch);
        }
        assertNodeOrder("Visited nodes", mainBranch.visited, 9, 8, 6, 4, 3);

        // Branch 2
        sideBranch.add(BRANCH2_END);
        sideBranch.add(exec.getNode("11"));
        sideBranch.add(exec.getNode("10"));
        sideBranch.add(exec.getNode("7"));
        for (FlowNode f : sideBranch.visited) {
            nodeMap.put(f, sideBranch);
        }
        assertNodeOrder("Visited nodes", sideBranch.visited, 12, 11, 10, 7);

        ForkScanner.Fork forked = mainBranch.split(nodeMap, (BlockStartNode)exec.getNode("4"), sideBranch);
        ForkScanner.FlowSegment splitSegment = (ForkScanner.FlowSegment)nodeMap.get(BRANCH1_END); // New branch
        Assert.assertNull(splitSegment.after);
        assertNodeOrder("Branch 1 split after fork", splitSegment.visited, 9, 8, 6);

        // Just the single node before the fork
        Assert.assertEquals(forked, mainBranch.after);
        assertNodeOrder("Head of flow, pre-fork", mainBranch.visited, 3);

        // Fork point
        Assert.assertEquals(forked, nodeMap.get(START_PARALLEL));
        ForkScanner.FlowPiece[] follows = {splitSegment, sideBranch};
        Assert.assertArrayEquals(follows, forked.following.toArray());

        // Branch 2
        Assert.assertEquals(sideBranch, nodeMap.get(BRANCH2_END));
        assertNodeOrder("Branch 2", sideBranch.visited, 12, 11, 10, 7);

        // Test me where splitting right at a fork point, where we should have a fork with and main branch shoudl become following
        // Along with side branch (branch2)
        nodeMap.clear();
        mainBranch = new ForkScanner.FlowSegment();
        sideBranch = new ForkScanner.FlowSegment();
        mainBranch.visited.add(exec.getNode("6"));
        mainBranch.visited.add(START_PARALLEL);
        sideBranch.visited.add(exec.getNode("7"));
        for (FlowNode f : mainBranch.visited) {
            nodeMap.put(f, mainBranch);
        }
        nodeMap.put(exec.getNode("7"), sideBranch);

        forked = mainBranch.split(nodeMap, (BlockStartNode)exec.getNode("4"), sideBranch);
        follows = new ForkScanner.FlowSegment[2];
        follows[0] = mainBranch;
        follows[1] = sideBranch;
        Assert.assertArrayEquals(follows, forked.following.toArray());
        assertNodeOrder("Branch1", mainBranch.visited, 6);
        Assert.assertNull(mainBranch.after);
        assertNodeOrder("Branch2", sideBranch.visited, 7);
        Assert.assertNull(sideBranch.after);
        Assert.assertEquals(forked, nodeMap.get(START_PARALLEL));
        Assert.assertEquals(mainBranch, nodeMap.get(exec.getNode("6")));
        Assert.assertEquals(sideBranch, nodeMap.get(exec.getNode("7")));
    }

    @Test
    public void testBranchMerge() throws Exception {
        FlowExecution exec = SIMPLE_PARALLEL_RUN.getExecution();

    }

    @Test
    public void testParallelBranchCreation() throws Exception {

    }
}
