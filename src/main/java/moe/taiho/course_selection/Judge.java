package moe.taiho.course_selection;

import com.google.gson.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import moe.taiho.course_selection.actors.CommonMessage.Reason;

/**
 * Created by cjr on 6/12/17.
 */
public class Judge implements Serializable {

    final public int id;
    public static Gson gson = new Gson();

    public Judge(int student) {
        id = student;
    }

    class ForTest implements Reason, Serializable {
        public ForTest() {
        }

        @Override
        public String toString() {
            return "For Test";
        }
    }

    class Success implements Reason, Serializable {
        public Success() {}

        @Override
        public String toString() {
            return "Success";
        }
    }

    class Selected implements Reason, Serializable {
        public Selected() {}

        @Override
        public String toString() {
            return "You have selected this course";
        }
    }

    class TimeConflict implements Reason, Serializable {
        public TimeConflict() {}

        @Override
        public String toString() {
            return "Time conflict with other selected course";
        }
    }

    public class JudgeResult {
        public boolean result;
        public Reason reason;

        public JudgeResult(boolean result_, Reason reason_) {
            result = result_;
            reason = reason_;
        }
    }

    public static class CourseInfo {
        public String year;
        public int semester;
        public String semesterNumber;
        public String courseNumber;
        public String[] weeks;
        public int weeksCount;
        public int weekday;
        public int start;
        public int end;
        public String startTime;
        public String endTime;
        public String classroomName;
        public String classroomShort;
        public String building;
        public String project;
        public double credit;
    }

    public static List<CourseInfo> courseInfo;

    static {
        courseInfo = new ArrayList<CourseInfo>();
        try {
            FileReader reader = new FileReader("/tmp/course_info.txt");
            BufferedReader br = new BufferedReader(reader);
            String courseText;
            while ((courseText = br.readLine()) != null) {
                courseInfo.add(gson.fromJson(courseText, CourseInfo.class));
                courseInfo.get(courseInfo.size() - 1).credit = courseInfo.size() % 3 + 1; //FIXME
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public class CourseTable {
        public List<Integer> selected = new ArrayList<Integer>();
        public double totalScore;
        public int[][] timeTable = new int[7][14];

        public void setScoreLimit(double limit) { scoreLimit = limit; }
        public boolean scoreLimitCheck() { return totalScore <= scoreLimit; }

        public void addCourse(int course) {
            CourseInfo c = courseInfo.get(course);
            selected.add(course);
            totalScore += c.credit;

            assert(inRange(c.weekday, 1, 7));
            assert(inRange(c.start, 1, 14));
            assert(inRange(c.end, 1, 14));
            for (int i = c.start; i <= c.end; i++)
                timeTable[c.weekday - 1][i - 1] = 1;
        }

        public boolean timeConflictCheck() {

        }

        public boolean duplicateCheck(int course) {
            for (Integer i : selected) {
                if (i == course)
                    return false;
            }
            return true;
        }

        private double scoreLimit;
        private boolean inRange(int x, int a, int b) {
            return a <= x && x <= b;
        }
    }

    public JudgeResult judge(int course) {
        JudgeResult res = new JudgeResult(true, new Success());
        return res;
    }

    @Override
    public String toString() {
        return this.toString();
    }
}
