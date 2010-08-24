/*
 * Copyright 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.tasks.diagnostics;

import org.gradle.api.Project;
import org.gradle.api.Rule;
import org.gradle.api.tasks.diagnostics.internal.ProjectReportRenderer;
import org.gradle.api.tasks.diagnostics.internal.TaskDetails;
import org.gradle.api.tasks.diagnostics.internal.TaskReportModel;
import org.gradle.api.tasks.diagnostics.internal.TaskReportRenderer;
import org.gradle.logging.StyledTextOutputFactory;

import java.io.IOException;

/**
 * <p>Displays a list of tasks in the project. It is used when you use the task list command-line option.</p>
 */
public class TaskReportTask extends AbstractReportTask {
    private TaskReportRenderer renderer = new TaskReportRenderer(getServices().get(StyledTextOutputFactory.class).create());

    private boolean detail;

    public ProjectReportRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(TaskReportRenderer renderer) {
        this.renderer = renderer;
    }

    public void setShowDetail(boolean detail) {
        this.detail = detail;
    }

    public boolean isDetail() {
        return detail;
    }

    public void generate(Project project) throws IOException {
        renderer.showDetail(isDetail());
        renderer.addDefaultTasks(project.getDefaultTasks());

        TaskReportModel model = new TaskReportModel();
        model.calculate(project.getTasks().getAll());

        for (String group : model.getGroups()) {
            renderer.startTaskGroup(group);
            for (TaskDetails task : model.getTasksForGroup(group)) {
                renderer.addTask(task);
                for (TaskDetails child : task.getChildren()) {
                    renderer.addChildTask(child);
                }
            }
        }

        renderer.completeTasks();
        for (Rule rule : project.getTasks().getRules()) {
            renderer.addRule(rule);
        }
    }
}
