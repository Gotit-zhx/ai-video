import unittest
from resume_storage import person_name


class IdentityTest(unittest.TestCase):
    def test_labels_spacing_and_column_order(self):
        for text, name in [
            ('个人简历\n姓名：张晓林\n联系电话：保密', '张晓林'),
            ('姓 名\n陈 佳 宁\n工作经历', '陈佳宁'),
            ('联系方式\n求职意向：销售代表\nHELLO\n张晓林 男 | 37岁\n个人优势', '张晓林'),
            ('某学院 本科 数字媒体技术 2021-2025\n某公司 行政前台\n陈佳宁\n女 | 年龄：24岁 |\n教育经历', '陈佳宁'),
        ]:
            with self.subTest(text=text): self.assertEqual(person_name(text), name)

    def test_no_guesses_from_jobs_file_ids_referees_or_ambiguous_names(self):
        for text in ['', '总经理秘书.pdf', '简历-a23b987c.pdf', '姓名：保密',
                     '工作经历\n联系人 王同学 男 | 35岁', '姓名：张晓林\n姓名：陈佳宁']:
            with self.subTest(text=text): self.assertEqual(person_name(text), '')
