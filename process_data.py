import pandas as pd
df = pd.DataFrame({'A': [1, 2]})
# Intentional error: pandas does not have a 'to_xml_super_fast' method
df.to_xml('output.xml', encoding='utf-8', pretty_print=True)
