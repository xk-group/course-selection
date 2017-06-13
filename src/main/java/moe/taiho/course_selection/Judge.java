package moe.taiho.course_selection;

import com.google.gson.*;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
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
        courseTable = new CourseTable(id);
        courseTable.setScoreLimit(32);
        //courseTable.importCourses(null); // according to studentId
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

    class ScoreLimitExceed implements Reason, Serializable {
        public ScoreLimitExceed() {}

        @Override
        public String toString() {
            return "You have exceeded the credits limit";
        }
    }

    class NotSelected implements Reason, Serializable {
        public NotSelected() {}

        @Override
        public String toString() {
            return "You haven't selected this course";
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
        public String courseNumber; // "FINE110056.01"
        public List<Integer> peroids;
        public double credit;

        public CourseInfo() {
            peroids = new ArrayList<Integer>();
        }
    }

    public class CourseJson {
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

    public static List<CourseJson> courseJson;
    public static List<CourseInfo> courseInfo = new ArrayList<CourseInfo>();
    public static HashMap<String, Integer> name2Id = new HashMap<String, Integer>();
    public static int idCount = 0;

    static {
        courseJson = new ArrayList<CourseJson>();
        try {
            InputStream input;
            ClassLoader classLoader = Judge.class.getClassLoader();
            input = classLoader.getResourceAsStream("course_info.json");
            if (input == null) {
                System.out.println("faile to load resources");
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(input));
            String courseText;
            while ((courseText = br.readLine()) != null) {
                courseJson.add(gson.fromJson(courseText, CourseJson.class));
                courseJson.get(courseJson.size() - 1).credit = courseJson.size() % 3 + 1; //FIXME
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (CourseJson j : courseJson) {
            int id;
            if (!name2Id.containsKey(j.courseNumber)) {
                name2Id.put(j.courseNumber, idCount++);
                id = idCount - 1;

                CourseInfo c = new CourseInfo();
                c.courseNumber = j.courseNumber;
                c.credit = j.credit;

                assert(inRange(j.weekday, 1, 7));
                assert(inRange(j.start, 1, 14));
                assert(inRange(j.end, 1, 14));
                for (int i = j.start; i <= j.end; i++)
                    c.peroids.add(timeEncode(j.weekday, i));
                courseInfo.add(c);
            } else {
                id = name2Id.get(j.courseNumber);

                assert(inRange(j.weekday, 1, 7));
                assert(inRange(j.start, 1, 14));
                assert(inRange(j.end, 1, 14));
                for (int i = j.start; i <= j.end; i++)
                    courseInfo.get(id).peroids.add(timeEncode(j.weekday, i));
            }
        }
    }

    public class CourseTable implements Serializable {
        public int id;
        public List<Integer> selected = new ArrayList<Integer>();
        public double totalScore;
        public int[][] timeTable = new int[7][14];

        public CourseTable(int student) { id = student; }

        public void setScoreLimit(double limit) { scoreLimit = limit; }
        public boolean scoreLimitCheck(int course) { return totalScore + courseInfo.get(course).credit <= scoreLimit; }

        public void addCourse(int course) {
            CourseInfo c = courseInfo.get(course);
            selected.add(course);
            totalScore += c.credit;

            for (Integer i : c.peroids) {
                timeTable[i / 14][i % 14] = 1;
            }
        }

        public void dropCourse(int course) {
            CourseInfo c = courseInfo.get(course);
            selected.remove(selected.indexOf(course));
            totalScore -= c.credit;

            for (Integer i : c.peroids) {
                timeTable[i / 14][i % 14] = 0;
            }
        }

        public boolean timeConflictCheck(int course) {
            CourseInfo c = courseInfo.get(course);
            for (Integer i : c.peroids)
                if (timeTable[i / 14][i % 14] == 1)
                    return false;
            return true;
        }

        public boolean duplicateCheck(int course) {
            for (Integer i : selected) {
                if (i.equals(course))
                    return false;
            }
            return true;
        }

        public void importCourses(List<Integer> courses) {
            for (Integer course : courses) {
                if (duplicateCheck(course) && timeConflictCheck(course) && scoreLimitCheck(course))
                    addCourse(course);
            }
        }

        public String selected2Json() {
            String ret = "";
            for (Integer s : selected) {
                ret += gson.toJson(courseInfo.get(s)) + "\n";
            }
            return ret;
        }

        private double scoreLimit;

        @Override
        public String toString() {
            return new Integer(id).toString();
        }
    }

    private static boolean inRange(int x, int a, int b) {
        return a <= x && x <= b;
    }
    private static int timeEncode(int weekday, int time) {
        return (weekday - 1) * 14 + time - 1;
    }

    public CourseTable courseTable;

    public JudgeResult registerCheck(int course) {
        JudgeResult ret = new JudgeResult(true, new Success());
        if (!courseTable.duplicateCheck(course)) {
            ret.result = false;
            ret.reason = new Selected();
            return ret;
        }
        if (!courseTable.scoreLimitCheck(course)) {
            ret.result = false;
            ret.reason = new ScoreLimitExceed();
            return ret;
        }
        if (!courseTable.timeConflictCheck(course)) {
            ret.result = false;
            ret.reason = new TimeConflict();
            return ret;
        }
        return ret;
    }

    public JudgeResult dropCheck(int course) {
        JudgeResult ret = new JudgeResult(true, new Success());
        if (courseTable.duplicateCheck(course)) {
            ret.result = false;
            ret.reason = new NotSelected();
            return ret;
        }
        return ret;
    }

    public String showTable() {
        return courseTable.selected2Json();
    }

    @Override
    public String toString() {
        return this.toString();
    }
}
